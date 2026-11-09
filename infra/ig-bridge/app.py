"""
ig-bridge: a small HTTP sidecar that lets the platform backend send Instagram direct messages from a shop's own account the way a person would,
through the unofficial private API wrapped by `instagrapi`.

RISK: Meta forbids automation of personal and business accounts. An account used through this service can be restricted or banned. The backend adds pacing,
warm-up and stop-at-the-first-block rules; this service only translates calls and maps errors. It was written against instagrapi's documented API and tested with a
MOCKED client only: it has never talked to a real Instagram account.

Security: every endpoint except /health needs the shared secret header X-Bridge-Secret. Passwords and 2FA secrets are used in memory for the login call only; they are never
written to disk or to the log. What is stored per shop is instagrapi's settings JSON (session cookies and a stable device fingerprint), in a 0600 file on the mounted volume.
"""
import hmac
import json
import logging
import os
import re
import threading
import time
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

try:  # the library is only absent in the unit tests, which replace make_client
    from instagrapi import Client
    from instagrapi import exceptions as ig_exc
except ImportError:  # pragma: no cover
    Client = None
    ig_exc = None

log = logging.getLogger("ig-bridge")
logging.basicConfig(level=os.environ.get("IG_BRIDGE_LOG", "INFO"))

DATA_DIR = Path(os.environ.get("IG_BRIDGE_DATA", "/data"))
SECRET = os.environ.get("IG_BRIDGE_SECRET", "")
MAX_TYPING_MS = 40_000
TENANT_RE = re.compile(r"^[0-9a-fA-F-]{32,36}$")


def _exc(name: str):
    """The instagrapi exception class of that name, or a stand-in that never matches when the library (or that class in this version) is missing."""
    return getattr(ig_exc, name, type(name, (Exception,), {}))


EXC = {n: _exc(n) for n in (
    "FeedbackRequired", "ChallengeRequired", "LoginRequired", "PleaseWaitFewMinutes", "RateLimitError", "UserNotFound", "BadPassword",
    "InvalidUser", "TwoFactorRequired", "ChallengeUnknownStep", "SelectContactPointRecoveryForm", "RecaptchaChallengeForm", "ReloginAttemptExceeded",
    "ClientForbiddenError", "ClientThrottledError", "ClientLoginRequired", "ClientConnectionError")}

# anything in here means "stop now": the account is being limited, challenged or throttled
BLOCK = tuple(EXC[n] for n in ("FeedbackRequired", "ChallengeRequired", "PleaseWaitFewMinutes", "RateLimitError", "ChallengeUnknownStep",
                               "SelectContactPointRecoveryForm", "RecaptchaChallengeForm", "ReloginAttemptExceeded", "ClientForbiddenError", "ClientThrottledError"))
EXPIRED = tuple(EXC[n] for n in ("LoginRequired", "ClientLoginRequired"))

app = FastAPI(title="ig-bridge", docs_url=None, redoc_url=None, openapi_url=None)
_clients: dict = {}
_locks: dict = {}
_registry_lock = threading.Lock()


def require_secret(x_bridge_secret: Optional[str] = Header(default=None)):
    if not SECRET or not x_bridge_secret or not hmac.compare_digest(SECRET.encode(), x_bridge_secret.encode()):
        raise HTTPException(status_code=401, detail="unauthorized")


def tenant_key(tenant: str) -> str:
    if not TENANT_RE.match(tenant or ""):
        raise HTTPException(status_code=400, detail="bad tenant")
    return tenant.lower()


def lock_for(tenant: str) -> threading.Lock:
    with _registry_lock:
        return _locks.setdefault(tenant, threading.Lock())


def settings_path(tenant: str) -> Path:
    return DATA_DIR / f"{tenant}.json"


def proxy_for(tenant: str) -> Optional[str]:
    """Optional per-shop proxy: IG_BRIDGE_PROXIES='{"<tenant uuid>": "http://user:pass@host:port"}'. One shop, one exit address."""
    try:
        return json.loads(os.environ.get("IG_BRIDGE_PROXIES", "{}")).get(tenant)
    except ValueError:
        return None


def make_client(tenant: str):
    """A client for the shop with its own persisted device fingerprint (settings file) and optional proxy."""
    cl = Client()
    cl.delay_range = [2, 5]   # instagrapi's own small random pause between its requests
    proxy = proxy_for(tenant)
    if proxy:
        cl.set_proxy(proxy)
    path = settings_path(tenant)
    if path.exists():
        cl.load_settings(path)
    return cl


def save_settings(cl, tenant: str) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    path = settings_path(tenant)
    cl.dump_settings(path)
    os.chmod(path, 0o600)


def client_for(tenant: str):
    cl = _clients.get(tenant)
    if cl is None and settings_path(tenant).exists():
        cl = make_client(tenant)
        _clients[tenant] = cl
    return cl


class LoginIn(BaseModel):
    tenant: str
    username: str
    password: str
    totp: Optional[str] = None

    def __repr__(self) -> str:   # never print the password
        return f"LoginIn(tenant={self.tenant}, username={self.username}, password=***)"


class TenantIn(BaseModel):
    tenant: str


class SendIn(BaseModel):
    tenant: str
    username_or_thread: str
    text: str
    typing_ms: int = 0


def code_for(totp: Optional[str], cl) -> Optional[str]:
    """A 6-digit code is used as given; anything else is treated as the base32 2FA secret and turned into the current code."""
    if not totp:
        return None
    t = totp.strip().replace(" ", "")
    return t if re.fullmatch(r"\d{6}", t) else cl.totp_generate_code(t)


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/sessions/login", dependencies=[Depends(require_secret)])
def login(body: LoginIn):
    tenant = tenant_key(body.tenant)
    with lock_for(tenant):
        try:
            cl = make_client(tenant)
            cl.login(body.username, body.password, verification_code=code_for(body.totp, cl) or "")
            save_settings(cl, tenant)
            _clients[tenant] = cl
            return {"ok": True, "account": body.username}
        except (EXC["ChallengeRequired"], EXC["TwoFactorRequired"], EXC["ChallengeUnknownStep"], EXC["SelectContactPointRecoveryForm"], EXC["RecaptchaChallengeForm"]) as e:
            log.warning("login needs a challenge: %s", type(e).__name__)
            return {"ok": False, "error": "CHALLENGE"}
        except (EXC["BadPassword"], EXC["InvalidUser"]) as e:
            log.warning("login refused: %s", type(e).__name__)
            return {"ok": False, "error": "BAD_CREDENTIALS"}
        except Exception as e:  # noqa: BLE001 - class name only: the message could echo what was sent
            log.warning("login failed: %s", type(e).__name__)
            return {"ok": False, "error": "FAILED"}


@app.post("/sessions/logout", dependencies=[Depends(require_secret)])
def logout(body: TenantIn):
    tenant = tenant_key(body.tenant)
    with lock_for(tenant):
        cl = _clients.pop(tenant, None)
        try:
            if cl is not None:
                cl.logout()
        except Exception as e:  # noqa: BLE001
            log.info("logout call failed (session dropped anyway): %s", type(e).__name__)
        settings_path(tenant).unlink(missing_ok=True)
    return {"ok": True}


@app.get("/sessions/{tenant}/status", dependencies=[Depends(require_secret)])
def status(tenant: str):
    tenant = tenant_key(tenant)
    exists = settings_path(tenant).exists() or tenant in _clients
    cl = client_for(tenant)
    logged_in = bool(cl is not None and getattr(cl, "user_id", None))
    username = getattr(cl, "username", None) if logged_in else None
    return {"exists": exists, "logged_in": logged_in, "account": username}


def resolve_user(cl, ref: str) -> int:
    ref = (ref or "").strip().lstrip("@")
    if ref.isdigit():
        return int(ref)
    return int(cl.user_id_from_username(ref))


@app.post("/messages/send", dependencies=[Depends(require_secret)])
def send(body: SendIn):
    tenant = tenant_key(body.tenant)
    with lock_for(tenant):
        cl = client_for(tenant)
        if cl is None:
            return {"result": "SESSION_EXPIRED", "detail": "no session"}
        try:
            pk = resolve_user(cl, body.username_or_thread)
            # like a person: open the thread, mark it seen, "type" for a while, then send
            typing = max(0, min(int(body.typing_ms), MAX_TYPING_MS)) / 1000.0
            try:
                thread = cl.direct_thread_by_participants([pk])
                thread_id = (thread.get("thread", {}) if isinstance(thread, dict) else {}).get("thread_id") if thread else None
                seen = getattr(cl, "direct_send_seen", None)
                if thread_id and seen:
                    seen(thread_id)
            except Exception as e:  # noqa: BLE001 - the pre-steps are best effort
                if isinstance(e, BLOCK + EXPIRED):
                    raise
                log.info("pre-send steps skipped: %s", type(e).__name__)
            if typing:
                time.sleep(typing)
            cl.direct_send(body.text, user_ids=[pk])
            save_settings(cl, tenant)
            return {"result": "SENT"}
        except EXC["UserNotFound"]:
            return {"result": "NOT_FOUND", "detail": "user not found"}
        except EXPIRED as e:
            return {"result": "SESSION_EXPIRED", "detail": type(e).__name__}
        except BLOCK as e:
            log.warning("block signal: %s", type(e).__name__)
            return {"result": "BLOCKED", "detail": type(e).__name__}
        except ValueError:
            return {"result": "NOT_FOUND", "detail": "bad recipient"}
        except Exception as e:  # noqa: BLE001
            log.warning("send failed: %s", type(e).__name__)
            return {"result": "FAILED", "detail": type(e).__name__}

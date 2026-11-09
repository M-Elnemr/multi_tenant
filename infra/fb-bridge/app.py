"""
fb-bridge: a small HTTP sidecar that lets the platform backend send Messenger messages the way a person would, through the unofficial web endpoints wrapped by `fbchat-v2`.

RISK: Meta forbids automation of personal accounts and Pages. An account used through this service can be restricted or banned. The backend adds pacing, caps and stop-at-the-first-block
rules; this service only translates calls and maps errors.

NOT VERIFIED: this was written from reading fbchat-v2 2.3.4 (src/_core/_session.py, src/_messaging/_send.py) and tested with a FAKE transport only. It has never talked to Facebook.
Acting as a Page (the `i_user=<page_id>` cookie) is how the web Business Suite does it, but fbchat-v2 does not document it; login() therefore checks that the `actorID` Facebook
reports is really the Page id and refuses (FAILED / PAGE_IDENTITY_NOT_APPLIED) when it is not, instead of silently sending as the personal profile.
Addressing a Page-inbox contact by its page-scoped id (PSID) is also unproven.

Security: every endpoint except /health needs the shared secret header X-Bridge-Secret. Cookies are used in memory and stored per shop in a 0600 file on the mounted volume;
they are never written to the log.
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

log = logging.getLogger("fb-bridge")
logging.basicConfig(level=os.environ.get("FB_BRIDGE_LOG", "INFO"))

DATA_DIR = Path(os.environ.get("FB_BRIDGE_DATA", "/data"))
SECRET = os.environ.get("FB_BRIDGE_SECRET", "")
MAX_TYPING_MS = 40_000
TENANT_RE = re.compile(r"^[0-9a-fA-F-]{32,36}$")
PAGE_ID_RE = re.compile(r"^\d{5,25}$")
RECIPIENT_RE = re.compile(r"^\d{5,25}$")

# Facebook error codes seen on the web send endpoint; best effort, anything else is FAILED.
EXPIRED_CODES = {1357001, 1357004, 1357031}
BLOCK_CODES = {1404006, 1404078, 1404102, 1545003, 368}
NOT_FOUND_CODES = {1545012, 1545010, 1545026}


class SessionExpired(Exception):
    """Facebook says the cookies are no longer valid."""


class Checkpoint(Exception):
    """Facebook asks for a security check (checkpoint)."""


class RateLimited(Exception):
    """Facebook throttled or limited the account."""


class Transport:
    """What the bridge needs from Facebook. The default one uses fbchat-v2; tests replace it with a fake that never touches the network."""

    def home(self, cookies: str) -> Optional[dict]:
        """Session data for the cookie string (includes FacebookID = actorID), or None when Facebook did not give a logged-in page."""
        raise NotImplementedError

    def send(self, data: dict, recipient: str, text: str) -> dict:
        """fbchat-v2's result dict: {"success": 1, ...} or {"error": 1, "payload": {"error-code":..., "error-description":...}}."""
        raise NotImplementedError


class FbchatTransport(Transport):
    def home(self, cookies: str) -> Optional[dict]:
        import asyncio
        import contextlib
        import io

        from fbchat_v2 import dataGetHome
        # the library prints diagnostics to stdout; keep them out of the log
        with contextlib.redirect_stdout(io.StringIO()):
            return asyncio.run(dataGetHome(cookies))

    def send(self, data: dict, recipient: str, text: str) -> dict:
        import httpx
        from fbchat_v2 import SendAPI
        try:
            return SendAPI().send_blocking(data, text, recipient, typeChat="user")
        except httpx.HTTPStatusError as e:
            code = e.response.status_code
            if code in (401, 403):
                raise SessionExpired() from None
            if code == 429:
                raise RateLimited() from None
            raise RuntimeError(f"http {code}") from None


transport: Transport = FbchatTransport()
app = FastAPI(title="fb-bridge", docs_url=None, redoc_url=None, openapi_url=None)
_data: dict = {}   # tenant -> session data from home() (fb_dtsg etc.), memory only
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


def session_path(tenant: str) -> Path:
    return DATA_DIR / f"{tenant}.json"


def build_cookie_string(cookies: str, page_id: str) -> str:
    """The pasted cookie string with `i_user=<page_id>` set, so facebook.com serves the Page identity. Accepts `a=b; c=d` and drops any earlier i_user."""
    parts = {}
    for part in (cookies or "").split(";"):
        part = part.strip()
        if "=" in part:
            k, _, v = part.partition("=")
            if k.strip():
                parts[k.strip()] = v.strip()
    parts.pop("i_user", None)
    parts["i_user"] = page_id
    return "; ".join(f"{k}={v}" for k, v in parts.items())


def has_login_cookies(cookies: str) -> bool:
    names = {p.split("=", 1)[0].strip() for p in (cookies or "").split(";") if "=" in p}
    return "c_user" in names and "xs" in names


def write_session(tenant: str, cookie_string: str, page_id: str) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    path = session_path(tenant)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"cookies": cookie_string, "page_id": page_id, "saved_at": int(time.time())}, f)
    os.chmod(path, 0o600)


def read_session(tenant: str) -> Optional[dict]:
    try:
        return json.loads(session_path(tenant).read_text())
    except (OSError, ValueError):
        return None


class LoginIn(BaseModel):
    tenant: str
    cookies: str
    page_id: str

    def __repr__(self) -> str:   # never print the cookies
        return f"LoginIn(tenant={self.tenant}, page_id={self.page_id}, cookies=***)"


class TenantIn(BaseModel):
    tenant: str


class SendIn(BaseModel):
    tenant: str
    recipient: str
    text: str
    typing_ms: int = 0


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/sessions/login", dependencies=[Depends(require_secret)])
def login(body: LoginIn):
    tenant = tenant_key(body.tenant)
    page_id = (body.page_id or "").strip()
    if not PAGE_ID_RE.match(page_id) or not has_login_cookies(body.cookies):
        return {"ok": False, "error": "BAD_CREDENTIALS"}
    cookie_string = build_cookie_string(body.cookies, page_id)
    with lock_for(tenant):
        try:
            data = transport.home(cookie_string)
        except Checkpoint:
            return {"ok": False, "error": "CHECKPOINT"}
        except Exception as e:  # noqa: BLE001 - class name only: the message could echo the cookies
            log.warning("login failed: %s", type(e).__name__)
            return {"ok": False, "error": "FAILED"}
        if not data:
            log.warning("login refused: no logged-in page for tenant %s", tenant)
            return {"ok": False, "error": "BAD_CREDENTIALS"}
        actor = str(data.get("FacebookID") or "")
        if actor != page_id:
            # i_user did not take effect: sending now would go out as the personal profile, which must never happen
            log.warning("login refused: acting id %s is not the page id for tenant %s", actor, tenant)
            return {"ok": False, "error": "FAILED", "detail": "PAGE_IDENTITY_NOT_APPLIED"}
        write_session(tenant, cookie_string, page_id)
        _data[tenant] = data
        return {"ok": True, "account": page_id}


@app.post("/sessions/logout", dependencies=[Depends(require_secret)])
def logout(body: TenantIn):
    tenant = tenant_key(body.tenant)
    with lock_for(tenant):
        _data.pop(tenant, None)
        session_path(tenant).unlink(missing_ok=True)
    return {"ok": True}


@app.get("/sessions/{tenant}/status", dependencies=[Depends(require_secret)])
def status(tenant: str):
    tenant = tenant_key(tenant)
    saved = read_session(tenant)
    # validity is only known on the next call; send then reports SESSION_EXPIRED
    present = saved is not None
    return {"exists": present, "logged_in": present, "account": (saved or {}).get("page_id")}


def _map_failure(result: dict) -> str:
    payload = (result or {}).get("payload") or {}
    try:
        code = int(payload.get("error-code"))
    except (TypeError, ValueError):
        code = None
    if code in EXPIRED_CODES:
        return "SESSION_EXPIRED"
    if code in BLOCK_CODES:
        return "BLOCKED"
    if code in NOT_FOUND_CODES:
        return "NOT_FOUND"
    return "FAILED"


@app.post("/messages/send", dependencies=[Depends(require_secret)])
def send(body: SendIn):
    tenant = tenant_key(body.tenant)
    recipient = (body.recipient or "").strip()
    if not RECIPIENT_RE.match(recipient):
        return {"result": "NOT_FOUND", "detail": "bad recipient"}
    with lock_for(tenant):
        saved = read_session(tenant)
        if saved is None:
            return {"result": "SESSION_EXPIRED", "detail": "no session"}
        try:
            data = _data.get(tenant)
            if data is None:   # after a restart: rebuild the token set from the saved cookies
                data = transport.home(saved["cookies"])
                if not data or str(data.get("FacebookID") or "") != saved["page_id"]:
                    return {"result": "SESSION_EXPIRED", "detail": "session no longer valid"}
                _data[tenant] = data
            typing = max(0, min(int(body.typing_ms), MAX_TYPING_MS)) / 1000.0
            if typing:
                time.sleep(typing)
            result = transport.send(data, recipient, body.text)
            if (result or {}).get("success"):
                return {"result": "SENT"}
            mapped = _map_failure(result)
            if mapped == "SESSION_EXPIRED":
                _data.pop(tenant, None)
            if mapped in ("BLOCKED", "FAILED"):
                log.warning("send not accepted: %s code=%s", mapped, ((result or {}).get("payload") or {}).get("error-code"))
            return {"result": mapped, "detail": "facebook refused"}
        except SessionExpired:
            _data.pop(tenant, None)
            return {"result": "SESSION_EXPIRED", "detail": "SessionExpired"}
        except Checkpoint:
            return {"result": "BLOCKED", "detail": "Checkpoint"}
        except RateLimited:
            return {"result": "BLOCKED", "detail": "RateLimited"}
        except Exception as e:  # noqa: BLE001
            log.warning("send failed: %s", type(e).__name__)
            return {"result": "FAILED", "detail": type(e).__name__}

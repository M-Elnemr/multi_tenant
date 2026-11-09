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
import random
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
FOLLOWERS_FRIENDLY_NAME = "ProfileCometAppCollectionListRendererPaginationQuery"
# Facebook changes persisted-query ids from time to time: when followers stop loading, set FB_DOCID_FOLLOWERS to the new one (see the README).
DOCID_FOLLOWERS = os.environ.get("FB_DOCID_FOLLOWERS", "28822068060820720")

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

    def threads(self, data: dict, before: Optional[int], limit: int) -> list:
        """One page of inbox thread nodes (raw graphql dicts), newest first; `before` = updated_time_precise (ms) of the oldest thread of the previous page."""
        raise NotImplementedError

    def followers_html(self, data: dict, page_id: str) -> str:
        """The HTML of the Page's followers tab (facebook.com/<page_id>/followers), fetched with the Page session."""
        raise NotImplementedError

    def followers_graphql(self, data: dict, doc_id: str, variables: dict, lsd: Optional[str]) -> str:
        """The raw text of one POST /api/graphql/ answer (ProfileCometAppCollectionListRendererPaginationQuery)."""
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


    def threads(self, data: dict, before: Optional[int], limit: int) -> list:
        # fbchat-v2's own list call has a fixed limit of 50 and no paging, so the same request is built here with `before`
        import contextlib
        import io

        import httpx
        from fbchat_v2._core._utils import formAll, mainRequests, send_request
        from fbchat_v2._features._thread._all_thread_data import _URL, _parse_graphqlbatch_response
        form = formAll(data, requireGraphql=False)
        form["queries"] = json.dumps({"o0": {"doc_id": "3336396659757871", "query_params": {
            "limit": limit, "before": before, "tags": ["INBOX"], "includeDeliveryReceipts": False, "includeSeqID": True}}}, separators=(",", ":"))
        args = mainRequests(_URL, form, data["cookieFacebook"])
        args["timeout"] = 60.0
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                response = send_request(args)
            response.raise_for_status()
        except httpx.HTTPStatusError as e:
            code = e.response.status_code
            if code in (401, 403):
                raise SessionExpired() from None
            if code == 429:
                raise RateLimited() from None
            raise RuntimeError(f"http {code}") from None
        parsed = _parse_graphqlbatch_response(response.text)
        o0 = parsed.get("o0") or {}
        if o0.get("errors"):
            raise RuntimeError("graphql errors")
        return ((((o0.get("data") or {}).get("viewer") or {}).get("message_threads") or {}).get("nodes")) or []

    def followers_html(self, data: dict, page_id: str) -> str:
        import contextlib
        import io

        import httpx
        from fbchat_v2._core._utils import Headers, parse_cookie_string
        headers = Headers(None, "www.facebook.com")
        headers.update({"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Sec-Fetch-Dest": "document", "Sec-Fetch-Mode": "navigate",
                        "Sec-Fetch-Site": "same-origin", "Upgrade-Insecure-Requests": "1", "Referer": "https://www.facebook.com/"})
        headers.pop("Origin", None)
        headers.pop("Host", None)
        try:
            with contextlib.redirect_stdout(io.StringIO()), httpx.Client(timeout=60.0, follow_redirects=True, headers=headers, cookies=parse_cookie_string(data["cookieFacebook"])) as client:
                response = client.get(f"https://www.facebook.com/{page_id}/followers")
            response.raise_for_status()
        except httpx.HTTPStatusError as e:
            _raise_for_status(e.response.status_code)
        final = str(response.url)
        if "/login" in final:
            raise SessionExpired()
        if "/checkpoint" in final:
            raise Checkpoint()
        return response.text

    def followers_graphql(self, data: dict, doc_id: str, variables: dict, lsd: Optional[str]) -> str:
        import contextlib
        import io

        import httpx
        from fbchat_v2._core._utils import formAll, mainRequests, send_request
        form = formAll(data, FOLLOWERS_FRIENDLY_NAME, doc_id)
        if lsd:
            form["lsd"] = lsd
        form["variables"] = json.dumps(variables, separators=(",", ":"))
        args = mainRequests("https://www.facebook.com/api/graphql/", form, data["cookieFacebook"])
        args["headers"]["X-FB-Friendly-Name"] = FOLLOWERS_FRIENDLY_NAME
        if lsd:
            args["headers"]["X-FB-LSD"] = lsd
        args["timeout"] = 60.0
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                response = send_request(args)
            response.raise_for_status()
        except httpx.HTTPStatusError as e:
            _raise_for_status(e.response.status_code)
        return response.text


def _raise_for_status(code: int):
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


MAX_THREADS_PER_CALL = 500
PAGE_SIZE = 50


def _thread_row(node: dict) -> Optional[dict]:
    """A 1:1 user thread as {id, name, last_activity_ms, unread}, or None for groups and anything without a user id."""
    key = node.get("thread_key") or {}
    if node.get("thread_type") == "GROUP" or key.get("thread_fbid") or node.get("is_group"):
        return None
    uid = str(key.get("other_user_id") or "")
    if not RECIPIENT_RE.match(uid):
        return None
    try:
        ms = int(str(node.get("updated_time_precise") or 0))
    except ValueError:
        ms = 0
    try:
        unread = int(node.get("unread_count") or 0)
    except (TypeError, ValueError):
        unread = 0
    name = node.get("name")
    if not name:
        parts = [e.get("node", {}).get("messaging_actor", {}) for e in ((node.get("all_participants") or {}).get("edges") or [])]
        name = next((p.get("name") for p in parts if str(p.get("id")) == uid and p.get("name")), None)
    return {"id": uid, "name": name, "last_activity_ms": ms, "unread": unread}


@app.get("/facebook/{tenant}/threads", dependencies=[Depends(require_secret)])
def threads(tenant: str, limit: int = 50, before: Optional[str] = None):
    tenant = tenant_key(tenant)
    limit = max(1, min(int(limit), MAX_THREADS_PER_CALL))
    cursor: Optional[int] = None
    if before:
        if not before.isdigit():
            return {"ok": False, "error": "FAILED", "detail": "bad before"}
        cursor = int(before)
    with lock_for(tenant):
        saved = read_session(tenant)
        if saved is None:
            return {"ok": False, "error": "SESSION_EXPIRED"}
        try:
            data = _data.get(tenant)
            if data is None:
                data = transport.home(saved["cookies"])
                if not data or str(data.get("FacebookID") or "") != saved["page_id"]:
                    return {"ok": False, "error": "SESSION_EXPIRED"}
                _data[tenant] = data
            rows, scanned, next_before, first = [], 0, None, True
            while scanned < limit:
                if not first:
                    time.sleep(random.uniform(1.0, 3.0))
                first = False
                want = min(PAGE_SIZE, limit - scanned)
                nodes = transport.threads(data, cursor, want)
                if not nodes:
                    next_before = None
                    break
                scanned += len(nodes)
                for n in nodes:
                    row = _thread_row(n)
                    if row:
                        rows.append(row)
                oldest = min((int(str(n.get("updated_time_precise") or 0)) for n in nodes), default=0)
                if len(nodes) < want or not oldest or oldest == cursor:
                    next_before = None
                    break
                cursor = next_before = oldest
            return {"ok": True, "threads": rows, "next_before": str(next_before) if next_before else None}
        except SessionExpired:
            _data.pop(tenant, None)
            return {"ok": False, "error": "SESSION_EXPIRED"}
        except (RateLimited, Checkpoint):
            return {"ok": False, "error": "RATE_LIMITED"}
        except Exception as e:  # noqa: BLE001
            log.warning("threads failed: %s", type(e).__name__)
            return {"ok": False, "error": "FAILED"}


# ---- the Page's followers ---------------------------------------------------------------------------------------------------------------
# The followers tab renders the first ~8-10 followers into its HTML (embedded JSON blobs) with the first end cursor and the id of the app collection; more load through
# POST /api/graphql/ (ProfileCometAppCollectionListRendererPaginationQuery). The shape of the graphql answer was never captured, so the parser below is TOLERANT. Its only assumptions:
#   * a follower is any object with a numeric `id` of at least 8 digits, a name (`name`, or `title`/`title.text` as text) and `__typename` == "User" or a facebook.com profile url
#   * pagination is any object under the key `page_info` / `pageInfo` carrying `end_cursor` and/or `has_next_page`
#   * the collection id is a string that is base64 of "app_collection:..." (starts with YXBwX2NvbGxlY3Rpb24)
MAX_FOLLOWERS_PER_CALL = 200
FOLLOWERS_PAGE_SIZE = 8
ID_RE = re.compile(r"^\d{8,25}$")
COLLECTION_RE = re.compile(r"YXBwX2NvbGxlY3Rpb24[A-Za-z0-9+/=_-]{4,200}")
LSD_RES = [re.compile(r'"LSD",\[\],\{"token":"([^"]+)"'), re.compile(r'name="lsd" value="([^"]+)"'), re.compile(r'"lsd":"([^"]+)"')]
SCRIPT_JSON_RE = re.compile(r"<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", re.S)
CURSOR_RE = re.compile(r'"end_cursor":"([^"]+)"')
NON_VANITY = {"profile.php", "people", "pages", "groups", "watch", "marketplace", "events", "help", "policies", "login", "privacy", "photo", "photo.php", "permalink.php", "story.php"}


def _text(v) -> Optional[str]:
    if isinstance(v, str):
        return v.strip() or None
    if isinstance(v, dict):
        return _text(v.get("text"))
    return None


def _vanity(url: Optional[str]) -> Optional[str]:
    m = re.match(r"^https?://(?:www\.|m\.|web\.)?facebook\.com/([^/?#]+)/?(?:[?#].*)?$", url or "")
    if not m:
        return None
    v = m.group(1)
    return None if v.lower() in NON_VANITY or v.isdigit() else v


def _user(o: dict) -> Optional[dict]:
    uid = o.get("id")
    uid = str(uid) if isinstance(uid, (str, int)) and not isinstance(uid, bool) else ""
    if not ID_RE.match(uid):
        return None
    name = _text(o.get("name")) or _text(o.get("title"))
    if not name:
        return None
    url = o.get("url") or o.get("profile_url") or o.get("profileUrl")
    url = url if isinstance(url, str) else None
    if o.get("__typename") != "User" and not (url and "facebook.com" in url):
        return None
    sub = None
    for k in ("subtitle_text", "subtitle", "subtitle_1", "subtitle_2", "bio_text"):
        sub = _text(o.get(k))
        if sub:
            break
    return {"id": uid, "name": name, "subtitle": sub, "vanity": _vanity(url)}


class Found:
    def __init__(self):
        self.users: dict = {}
        self.cursor: Optional[str] = None
        self.has_next: Optional[bool] = None
        self.collection: Optional[str] = None
        self.page_info_seen = False
        self.errors = False


def _walk(o, found: Found, collection: Optional[str] = None):
    if isinstance(o, list):
        for i in o:
            _walk(i, found, collection)
        return
    if not isinstance(o, dict):
        return
    cid = o.get("id")
    if isinstance(cid, str) and COLLECTION_RE.fullmatch(cid):
        collection = cid
    pi = o.get("page_info") if isinstance(o.get("page_info"), dict) else o.get("pageInfo")
    if isinstance(pi, dict) and ("end_cursor" in pi or "has_next_page" in pi):
        found.page_info_seen = True
        ec = pi.get("end_cursor")
        if isinstance(ec, str) and ec:
            found.cursor = ec
            if collection:
                found.collection = collection
        if isinstance(pi.get("has_next_page"), bool):
            found.has_next = pi["has_next_page"]
    if o.get("errors"):
        found.errors = True
    u = _user(o)
    if u and u["id"] not in found.users:
        found.users[u["id"]] = u
    for v in o.values():
        if isinstance(v, (dict, list)):
            _walk(v, found, collection)


def _json_objects(text: str) -> list:
    """Every JSON document in a graphql answer: plain JSON, a `for (;;);` guarded one, or several newline separated chunks (streamed answers)."""
    text = (text or "").strip()
    if text.startswith("for (;;);"):
        text = text[len("for (;;);"):].strip()
    try:
        return [json.loads(text)]
    except ValueError:
        pass
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("for (;;);"):
            line = line[len("for (;;);"):]
        if line.startswith("{") or line.startswith("["):
            try:
                out.append(json.loads(line))
            except ValueError:
                continue
    return out


def parse_followers(docs: list, exclude: set) -> Found:
    found = Found()
    for d in docs:
        _walk(d, found)
    for x in exclude:
        found.users.pop(x, None)
    return found


def parse_followers_html(html: str, exclude: set) -> Found:
    docs = []
    for blob in SCRIPT_JSON_RE.findall(html or ""):
        try:
            docs.append(json.loads(blob))
        except ValueError:
            continue
    found = parse_followers(docs, exclude)
    if found.cursor is None:   # the data may sit in a script that is not plain JSON: take the cursor by pattern
        m = CURSOR_RE.search(html or "")
        if m:
            found.cursor = m.group(1)
    if found.collection is None:
        m = COLLECTION_RE.search(html or "")
        if m:
            found.collection = m.group(0)
    return found


def lsd_from_html(html: str) -> Optional[str]:
    for rx in LSD_RES:
        m = rx.search(html or "")
        if m:
            return m.group(1)
    return None


def _followers_error(code: str, detail: Optional[str] = None):
    log.warning("followers: %s %s", code, detail or "")
    out = {"ok": False, "error": code}
    if detail:
        out["detail"] = detail
    return out


def _rows(users: dict) -> list:
    return [{"id": u["id"], "name": u["name"], "subtitle": u["subtitle"], "vanity": u["vanity"]} for u in users.values()]


@app.get("/facebook/{tenant}/followers", dependencies=[Depends(require_secret)])
def followers(tenant: str, cursor: Optional[str] = None, collection: Optional[str] = None, limit: int = 100):
    """One run of followers: no cursor = load the followers page (first batch, cursor, collection id), then page through graphql until `limit` is reached or the list ends.
    Returns every user it read (a page may overshoot `limit` by a few) so that `next_cursor` never skips anyone."""
    tenant = tenant_key(tenant)
    limit = max(1, min(int(limit), MAX_FOLLOWERS_PER_CALL))
    cursor = cursor or None
    collection = collection or None
    if collection and not COLLECTION_RE.fullmatch(collection):
        return {"ok": False, "error": "FAILED", "detail": "bad collection"}
    if cursor and not collection:
        return {"ok": False, "error": "FAILED", "detail": "collection required with cursor"}
    with lock_for(tenant):
        saved = read_session(tenant)
        if saved is None:
            return {"ok": False, "error": "SESSION_EXPIRED"}
        try:
            data = _data.get(tenant)
            if data is None:
                data = transport.home(saved["cookies"])
                if not data or str(data.get("FacebookID") or "") != saved["page_id"]:
                    return {"ok": False, "error": "SESSION_EXPIRED"}
                _data[tenant] = data
            exclude = {saved["page_id"], str(data.get("FacebookID") or "")}
            users: dict = {}
            lsd = None
            nxt = cursor
            if not cursor:
                html = transport.followers_html(data, saved["page_id"])
                lsd = lsd_from_html(html)
                first = parse_followers_html(html, exclude)
                if not first.users and not first.cursor:
                    return _followers_error("PARSE_EMPTY")
                users.update(first.users)
                nxt = first.cursor if first.has_next is not False else None
                collection = first.collection or collection
                if nxt and not collection:
                    return _followers_error("PARSE_EMPTY", "no collection id")
            while nxt and len(users) < limit:
                time.sleep(random.uniform(2.0, 5.0))
                variables = {"count": FOLLOWERS_PAGE_SIZE, "cursor": nxt, "scale": 2, "search": None, "id": collection,
                             "__relay_internal__pv__FBProfile_enable_perf_improv_gkrelayprovider": True}
                text = transport.followers_graphql(data, DOCID_FOLLOWERS, variables, lsd)
                docs = _json_objects(text)
                page = parse_followers(docs, exclude)
                if not page.users and not page.page_info_seen:
                    if users:   # keep the real pages; the next run retries from the same cursor
                        return {"ok": True, "users": _rows(users), "next_cursor": nxt, "collection": collection}
                    return _followers_error("DOCID_STALE" if (page.errors or not docs) else "PARSE_EMPTY")
                new = {k: v for k, v in page.users.items() if k not in users}
                users.update(new)
                following = page.cursor if page.has_next is not False else None
                nxt = following if following and following != nxt and new else None   # no news or the same cursor again: the list ended
            return {"ok": True, "users": _rows(users), "next_cursor": nxt, "collection": collection}
        except SessionExpired:
            _data.pop(tenant, None)
            return {"ok": False, "error": "SESSION_EXPIRED"}
        except (RateLimited, Checkpoint):
            return {"ok": False, "error": "RATE_LIMITED"}
        except Exception as e:  # noqa: BLE001
            log.warning("followers failed: %s", type(e).__name__)
            return {"ok": False, "error": "FAILED"}

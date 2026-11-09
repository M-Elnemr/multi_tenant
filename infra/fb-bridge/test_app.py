"""Unit tests with a FAKE transport: they check the HTTP contract, auth, the Page-identity guard, error mapping and that cookies never reach the log. They say nothing about real Facebook."""
import os
import stat
import tempfile

os.environ["FB_BRIDGE_SECRET"] = "test-secret"
os.environ["FB_BRIDGE_DATA"] = tempfile.mkdtemp()

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as bridge  # noqa: E402

TENANT = "11111111-2222-3333-4444-555555555555"
H = {"X-Bridge-Secret": "test-secret"}
PAGE = "100012345678901"
COOKIES = "datr=abc; c_user=555000111; xs=SECRET-XS-TOKEN-9; fr=zzz"


class FakeTransport(bridge.Transport):
    def __init__(self):
        self.home_calls, self.sent = [], []
        self.actor = PAGE            # what Facebook says the acting id is
        self.home_result = "ok"      # ok | none | raise | checkpoint
        self.send_result = {"success": 1, "payload": {"messageID": "m1"}}
        self.send_raise = None
        self.pages = []              # list of node lists, one per threads() call
        self.thread_calls = []
        self.threads_raise = None

    def home(self, cookies):
        self.home_calls.append(cookies)
        if self.home_result == "none":
            return None
        if self.home_result == "checkpoint":
            raise bridge.Checkpoint()
        if self.home_result == "raise":
            raise RuntimeError(f"boom {cookies}")
        return {"FacebookID": self.actor, "fb_dtsg": "t", "cookieFacebook": cookies}

    def send(self, data, recipient, text):
        if self.send_raise:
            raise self.send_raise
        self.sent.append((data["FacebookID"], recipient, text))
        return self.send_result


    def threads(self, data, before, limit):
        self.thread_calls.append((before, limit))
        if self.threads_raise:
            raise self.threads_raise
        return self.pages.pop(0) if self.pages else []


def node(uid, ms, name="N", group=False, unread=0):
    key = {"thread_fbid": uid} if group else {"other_user_id": uid}
    return {"thread_key": key, "name": name, "updated_time_precise": str(ms), "unread_count": unread}


@pytest.fixture(autouse=True)
def fake(monkeypatch):
    ft = FakeTransport()
    monkeypatch.setattr(bridge, "transport", ft)
    monkeypatch.setattr(bridge.time, "sleep", lambda s: None)
    bridge._data.clear()
    bridge.SECRET = "test-secret"
    bridge.session_path(TENANT).unlink(missing_ok=True)
    return ft


c = TestClient(bridge.app)


def login(cookies=COOKIES, page=PAGE):
    return c.post("/sessions/login", headers=H, json={"tenant": TENANT, "cookies": cookies, "page_id": page})


def send(recipient="1234567890", text="hello", typing=0):
    return c.post("/messages/send", headers=H, json={"tenant": TENANT, "recipient": recipient, "text": text, "typing_ms": typing}).json()


def test_secret_is_required():
    assert c.get("/health").status_code == 200
    assert c.post("/messages/send", json={"tenant": TENANT, "recipient": "1", "text": "x"}).status_code == 401
    assert c.post("/sessions/login", headers={"X-Bridge-Secret": "wrong"}, json={"tenant": TENANT, "cookies": COOKIES, "page_id": PAGE}).status_code == 401
    assert c.get(f"/sessions/{TENANT}/status").status_code == 401


def test_login_adds_i_user_and_stores_a_private_session_file(fake):
    assert login().json() == {"ok": True, "account": PAGE}
    assert fake.home_calls[0].endswith(f"i_user={PAGE}") and "c_user=555000111" in fake.home_calls[0]
    path = bridge.session_path(TENANT)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert c.get(f"/sessions/{TENANT}/status", headers=H).json() == {"exists": True, "logged_in": True, "account": PAGE}


def test_existing_i_user_is_replaced():
    cs = bridge.build_cookie_string("c_user=1; xs=2; i_user=999", PAGE)
    assert cs.count("i_user") == 1 and cs.endswith(f"i_user={PAGE}")


def test_send_logout_flow(fake):
    login()
    assert send(typing=1500) == {"result": "SENT"}
    assert fake.sent == [(PAGE, "1234567890", "hello")]
    assert c.post("/sessions/logout", headers=H, json={"tenant": TENANT}).json() == {"ok": True}
    assert c.get(f"/sessions/{TENANT}/status", headers=H).json() == {"exists": False, "logged_in": False, "account": None}
    assert send()["result"] == "SESSION_EXPIRED"


def test_typing_delay_is_applied_and_capped(fake, monkeypatch):
    slept = []
    monkeypatch.setattr(bridge.time, "sleep", slept.append)
    login()
    send(typing=1500)
    send(typing=10**9)
    assert slept == [1.5, bridge.MAX_TYPING_MS / 1000.0]


def test_send_after_restart_rebuilds_the_session(fake):
    login()
    bridge._data.clear()
    assert send() == {"result": "SENT"}
    assert len(fake.home_calls) == 2
    bridge._data.clear()
    fake.home_result = "none"
    assert send()["result"] == "SESSION_EXPIRED"


def test_login_refuses_when_the_page_identity_was_not_applied(fake):
    fake.actor = "555000111"   # facebook answered as the personal profile
    r = login().json()
    assert r["ok"] is False and r["error"] == "FAILED" and r["detail"] == "PAGE_IDENTITY_NOT_APPLIED"
    assert not bridge.session_path(TENANT).exists()


def test_login_errors(fake, caplog):
    assert login(cookies="datr=abc").json() == {"ok": False, "error": "BAD_CREDENTIALS"}
    assert login(page="abc").json() == {"ok": False, "error": "BAD_CREDENTIALS"}
    fake.home_result = "none"
    assert login().json() == {"ok": False, "error": "BAD_CREDENTIALS"}
    fake.home_result = "checkpoint"
    assert login().json() == {"ok": False, "error": "CHECKPOINT"}
    fake.home_result = "raise"
    assert login().json() == {"ok": False, "error": "FAILED"}
    assert "SECRET-XS-TOKEN-9" not in caplog.text


@pytest.mark.parametrize("payload,expected", [
    ({"error": 1, "payload": {"error-code": 1357001}}, "SESSION_EXPIRED"),
    ({"error": 1, "payload": {"error-code": 1404006}}, "BLOCKED"),
    ({"error": 1, "payload": {"error-code": 1545012}}, "NOT_FOUND"),
    ({"error": 1, "payload": {"error-code": 12}}, "FAILED"),
    ({"error": 1, "payload": {"error-description": "x"}}, "FAILED")])
def test_send_results_are_mapped(fake, payload, expected):
    login()
    fake.send_result = payload
    assert send()["result"] == expected


@pytest.mark.parametrize("exc,expected", [
    (bridge.SessionExpired(), "SESSION_EXPIRED"), (bridge.RateLimited(), "BLOCKED"), (bridge.Checkpoint(), "BLOCKED"), (RuntimeError("x"), "FAILED")])
def test_send_exceptions_are_mapped(fake, exc, expected):
    login()
    fake.send_raise = exc
    assert send()["result"] == expected


def test_bad_recipient_and_tenant():
    login()
    assert send(recipient="not-an-id")["result"] == "NOT_FOUND"
    assert c.get("/sessions/not-a-uuid/status", headers=H).status_code == 400


def test_cookies_never_reach_the_log(fake, caplog):
    caplog.set_level("DEBUG")
    login()
    fake.send_raise = RuntimeError(COOKIES)
    send()
    assert "SECRET-XS-TOKEN-9" not in caplog.text
    assert "SECRET-XS-TOKEN-9" not in repr(bridge.LoginIn(tenant=TENANT, cookies=COOKIES, page_id=PAGE))


def threads(limit=50, before=None):
    q = f"?limit={limit}" + (f"&before={before}" if before else "")
    return c.get(f"/facebook/{TENANT}/threads{q}", headers=H).json()


def test_threads_need_secret_and_session(fake):
    assert c.get(f"/facebook/{TENANT}/threads").status_code == 401
    assert threads() == {"ok": False, "error": "SESSION_EXPIRED"}


def test_threads_lists_only_user_threads_and_pages(fake, monkeypatch):
    slept = []
    monkeypatch.setattr(bridge.time, "sleep", slept.append)
    login()
    full = [node(str(2000000 + i), 9000 - i) for i in range(50)]
    full[3] = node("777777777", 8997, group=True)
    fake.pages = [full, [node("3000001", 100, name="Ali", unread=2)]]
    r = threads(limit=100)
    assert r["ok"] and r["next_before"] is None
    assert len(r["threads"]) == 50   # 49 users + 1 from page 2, group skipped
    assert r["threads"][-1] == {"id": "3000001", "name": "Ali", "last_activity_ms": 100, "unread": 2}
    assert "777777777" not in [t["id"] for t in r["threads"]]
    assert fake.thread_calls[0] == (None, 50) and fake.thread_calls[1][0] == 9000 - 49
    assert len(slept) == 1 and 1.0 <= slept[0] <= 3.0


def test_threads_returns_next_before_when_limit_reached(fake):
    login()
    fake.pages = [[node(str(2000000 + i), 9000 - i) for i in range(50)]]
    r = threads(limit=50)
    assert r["ok"] and len(r["threads"]) == 50 and r["next_before"] == str(9000 - 49)


def test_threads_limit_is_capped(fake):
    login()
    threads(limit=10**6)
    assert fake.thread_calls[0][1] == bridge.PAGE_SIZE


@pytest.mark.parametrize("exc,expected", [
    (bridge.SessionExpired(), "SESSION_EXPIRED"), (bridge.RateLimited(), "RATE_LIMITED"), (bridge.Checkpoint(), "RATE_LIMITED"), (RuntimeError("x"), "FAILED")])
def test_threads_errors_are_mapped(fake, exc, expected):
    login()
    fake.threads_raise = exc
    assert threads() == {"ok": False, "error": expected}

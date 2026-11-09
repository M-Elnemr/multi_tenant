"""Unit tests with a MOCKED instagrapi client. They check the HTTP contract, the auth, the error mapping and that secrets never reach the log. They say nothing about real Instagram."""
import os
import tempfile

os.environ["IG_BRIDGE_SECRET"] = "test-secret"
os.environ["IG_BRIDGE_DATA"] = tempfile.mkdtemp()

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as bridge  # noqa: E402

TENANT = "11111111-2222-3333-4444-555555555555"
H = {"X-Bridge-Secret": "test-secret"}
PASSWORD = "Sup3r-Secret-Pw!9"


class U:
    def __init__(self, pk, username):
        self.pk, self.username, self.full_name, self.is_private = pk, username, "Name " + username, False


class M:
    def __init__(self, pk, code, caption_text, comment_count):
        self.pk, self.code, self.caption_text, self.comment_count = pk, code, caption_text, comment_count


class Cm:
    def __init__(self, pk, text, user):
        self.pk, self.text, self.user = pk, text, user


class FakeClient:
    calls = []
    next_error = None
    sent = []
    login_error = None

    def __init__(self):
        self.user_id = None
        self.username = None

    def login(self, username, password, verification_code=""):
        if FakeClient.login_error:
            raise FakeClient.login_error
        self.user_id, self.username = 42, username

    def dump_settings(self, path):
        open(path, "w").write("{}")

    def load_settings(self, path):
        self.user_id = 42

    def user_id_from_username(self, name):
        if name == "ghost":
            raise bridge.EXC["UserNotFound"]()
        return 7

    def direct_thread_by_participants(self, ids):
        return {"thread": {"thread_id": "t1"}}

    def direct_send_seen(self, thread_id):
        pass

    def direct_send(self, text, user_ids):
        if FakeClient.next_error:
            raise FakeClient.next_error
        FakeClient.sent.append((text, user_ids))

    def logout(self):
        pass

    read_error = None

    def user_followers_v1_chunk(self, user_id, max_amount=0, max_id=""):
        if FakeClient.read_error:
            raise FakeClient.read_error
        FakeClient.calls = FakeClient.calls + [(user_id, max_amount, max_id)]
        i = int(max_id or 0)
        batch = [U(100 + n, f"u{n}") for n in range(i, min(i + max_amount, 5))]
        return batch, (str(i + len(batch)) if i + len(batch) < 5 else "")

    def user_medias(self, user_id, amount=0):
        if FakeClient.read_error:
            raise FakeClient.read_error
        return [M(111, "ABC", "hello", 3)]

    def media_comments(self, media_id, amount=20):
        if FakeClient.read_error:
            raise FakeClient.read_error
        return [Cm(5, "price?", U(200, "buyer"))]

    def totp_generate_code(self, secret):
        return "123456"


@pytest.fixture(autouse=True)
def fake(monkeypatch):
    FakeClient.next_error, FakeClient.login_error, FakeClient.sent, FakeClient.read_error, FakeClient.calls = None, None, [], None, []
    monkeypatch.setattr(bridge, "PAGE_SLEEP", (0, 0))
    bridge._clients.clear()
    monkeypatch.setattr(bridge, "make_client", lambda tenant: FakeClient())
    bridge.SECRET = "test-secret"
    yield


c = TestClient(bridge.app)


def login():
    return c.post("/sessions/login", headers=H, json={"tenant": TENANT, "username": "shop", "password": PASSWORD, "totp": "JBSWY3DPEHPK3PXP"})


def test_secret_is_required():
    assert c.get("/health").status_code == 200
    assert c.post("/messages/send", json={"tenant": TENANT, "username_or_thread": "a", "text": "x"}).status_code == 401
    assert c.post("/messages/send", headers={"X-Bridge-Secret": "wrong"}, json={"tenant": TENANT, "username_or_thread": "a", "text": "x"}).status_code == 401
    assert c.get(f"/sessions/{TENANT}/status").status_code == 401


def test_login_status_send_logout(caplog):
    assert login().json() == {"ok": True, "account": "shop"}
    st = c.get(f"/sessions/{TENANT}/status", headers=H).json()
    assert st["logged_in"] is True and st["exists"] is True
    r = c.post("/messages/send", headers=H, json={"tenant": TENANT, "username_or_thread": "mona", "text": "hello", "typing_ms": 0})
    assert r.json() == {"result": "SENT"} and FakeClient.sent == [("hello", [7])]
    assert c.post("/messages/send", headers=H, json={"tenant": TENANT, "username_or_thread": "123456", "text": "hi"}).json()["result"] == "SENT"   # a numeric reference is used as the user id
    assert c.post("/sessions/logout", headers=H, json={"tenant": TENANT}).json() == {"ok": True}
    assert c.get(f"/sessions/{TENANT}/status", headers=H).json()["logged_in"] is False
    assert PASSWORD not in caplog.text


@pytest.mark.parametrize("name,expected", [
    ("FeedbackRequired", "BLOCKED"), ("ChallengeRequired", "BLOCKED"), ("PleaseWaitFewMinutes", "BLOCKED"), ("RateLimitError", "BLOCKED"),
    ("LoginRequired", "SESSION_EXPIRED"), ("UserNotFound", "NOT_FOUND")])
def test_send_errors_are_mapped(name, expected):
    login()
    FakeClient.next_error = bridge.EXC[name]()
    r = c.post("/messages/send", headers=H, json={"tenant": TENANT, "username_or_thread": "mona", "text": "hello"})
    assert r.json()["result"] == expected


def test_unknown_error_is_failed_and_unknown_user_is_not_found():
    login()
    FakeClient.next_error = RuntimeError("boom")
    assert c.post("/messages/send", headers=H, json={"tenant": TENANT, "username_or_thread": "mona", "text": "hello"}).json()["result"] == "FAILED"
    FakeClient.next_error = None
    assert c.post("/messages/send", headers=H, json={"tenant": TENANT, "username_or_thread": "ghost", "text": "hello"}).json()["result"] == "NOT_FOUND"


def test_send_without_a_session_is_session_expired():
    assert c.post("/messages/send", headers=H, json={"tenant": "99999999-2222-3333-4444-555555555555", "username_or_thread": "m", "text": "x"}).json()["result"] == "SESSION_EXPIRED"


def test_login_errors_and_no_password_in_the_log(caplog):
    FakeClient.login_error = bridge.EXC["ChallengeRequired"]()
    assert login().json() == {"ok": False, "error": "CHALLENGE"}
    FakeClient.login_error = bridge.EXC["BadPassword"]()
    assert login().json() == {"ok": False, "error": "BAD_CREDENTIALS"}
    FakeClient.login_error = RuntimeError(f"echo {PASSWORD}")
    assert login().json() == {"ok": False, "error": "FAILED"}
    assert PASSWORD not in caplog.text


def test_bad_tenant_is_rejected():
    assert c.get("/sessions/not-a-uuid/status", headers=H).status_code == 400


def test_followers_pages_and_cursor():
    login()
    r = c.get(f"/instagram/{TENANT}/followers?amount=3", headers=H).json()
    assert r["ok"] and [u["username"] for u in r["users"]] == ["u0", "u1", "u2"] and r["next_cursor"] == "3"
    assert r["users"][0] == {"pk": "100", "username": "u0", "full_name": "Name u0", "is_private": False}
    r2 = c.get(f"/instagram/{TENANT}/followers?amount=10&cursor=3", headers=H).json()
    assert [u["username"] for u in r2["users"]] == ["u3", "u4"] and r2["next_cursor"] is None


def test_followers_are_fetched_in_small_pages():
    login()
    bridge.PAGE_SIZE = 2
    try:
        r = c.get(f"/instagram/{TENANT}/followers?amount=5", headers=H).json()
    finally:
        bridge.PAGE_SIZE = 50
    assert len(r["users"]) == 5 and len(FakeClient.calls) == 3


def test_media_recent_and_comments():
    login()
    m = c.get(f"/instagram/{TENANT}/media/recent?amount=12", headers=H).json()
    assert m == {"ok": True, "media": [{"id": "111", "code": "ABC", "caption": "hello", "comment_count": 3}]}
    cm = c.get(f"/instagram/{TENANT}/media/111/comments?amount=100", headers=H).json()
    assert cm == {"ok": True, "comments": [{"pk": "5", "text": "price?", "user": {"pk": "200", "username": "buyer", "full_name": "Name buyer", "is_private": False}}]}
    assert c.get(f"/instagram/{TENANT}/media/abc/comments", headers=H).status_code == 400


def test_listings_need_secret_and_session():
    assert c.get(f"/instagram/{TENANT}/followers").status_code == 401
    assert c.get(f"/instagram/{TENANT}/followers", headers=H).json() == {"ok": False, "error": "SESSION_EXPIRED"}
    assert c.get(f"/instagram/{TENANT}/media/recent", headers=H).json() == {"ok": False, "error": "SESSION_EXPIRED"}


@pytest.mark.parametrize("name,expected", [
    ("LoginRequired", "SESSION_EXPIRED"), ("ChallengeRequired", "CHALLENGE"), ("RateLimitError", "RATE_LIMITED"),
    ("PleaseWaitFewMinutes", "RATE_LIMITED"), ("FeedbackRequired", "RATE_LIMITED")])
def test_listing_errors_are_mapped(name, expected):
    login()
    FakeClient.read_error = bridge.EXC[name]()
    for path in ("followers", "media/recent", "media/111/comments"):
        assert c.get(f"/instagram/{TENANT}/{path}", headers=H).json() == {"ok": False, "error": expected}
    FakeClient.read_error = RuntimeError("boom")
    assert c.get(f"/instagram/{TENANT}/followers", headers=H).json() == {"ok": False, "error": "FAILED"}

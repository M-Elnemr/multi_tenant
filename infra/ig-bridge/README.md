# ig-bridge

A small FastAPI sidecar that sends Instagram direct messages from a shop's own account, one at a time, through the unofficial private API wrapped by
[`instagrapi`](https://github.com/subzeroid/instagrapi) (MIT). The platform backend decides who, when and how fast (see `HumanPolicy` / `ManualStyleRunner`);
this service only logs in, keeps the session, sends, and maps errors.

## Risk statement (read this)

* Meta's terms forbid automating Instagram. An account used through this service **can be restricted, challenged or permanently banned**. The shop accepts this
  explicitly in the dashboard before connecting; the platform is not responsible for it.
* `instagrapi` uses a private API that Instagram changes without notice. It can stop working at any time. Treat it as fragile.
* The pacing rules in the backend (40-120 s gaps, breaks, warm-up, 09:00-21:00, stop at the first block signal) lower the risk. They do not remove it.
* **Verified only with a mocked client** (`test_app.py`). It has never been run against a real Instagram account. Do a careful trial with a throw-away account first.
* Facebook Messenger is not supported by this service.

## API (all but `/health` need the header `X-Bridge-Secret: $IG_BRIDGE_SECRET`)

| Call | Body | Answer |
| --- | --- | --- |
| `GET /health` | - | `{ok:true}` |
| `POST /sessions/login` | `{tenant, username, password, totp?}` (`totp` = 6-digit code or the base32 2FA secret) | `{ok:true, account}` or `{ok:false, error: CHALLENGE|BAD_CREDENTIALS|FAILED}` |
| `POST /sessions/logout` | `{tenant}` | `{ok:true}` (also deletes the stored session file) |
| `GET /sessions/{tenant}/status` | - | `{exists, logged_in, account}` |
| `POST /messages/send` | `{tenant, username_or_thread, text, typing_ms}` | `{result: SENT|BLOCKED|SESSION_EXPIRED|NOT_FOUND|FAILED, detail?}` |

`tenant` is the shop's UUID. `username_or_thread` is an Instagram username, or a numeric Instagram user id. Note: the ids Meta's official webhooks give the backend
(Instagram-scoped ids) are NOT the same as the private API's user ids, so the backend sends the contact's username when its display name looks like one; otherwise a
lookup may answer `NOT_FOUND`. This is an open limitation.

### Read-only listings (new customers)

| Call | Answer |
| --- | --- |
| `GET /instagram/{tenant}/followers?amount=200&cursor=` | `{ok, users:[{pk, username, full_name, is_private}], next_cursor\|null}`; fetched in pages of 50 with random 2-6 s pauses (`IG_BRIDGE_PAGE_SLEEP_MIN/MAX`), at most 1000 per call, pass `next_cursor` back to continue |
| `GET /instagram/{tenant}/media/recent?amount=12` | `{ok, media:[{id, code, caption, comment_count}]}` |
| `GET /instagram/{tenant}/media/{id}/comments?amount=100` | `{ok, comments:[{pk, text, user:{pk, username, full_name, is_private}}]}` |

Errors: `{ok:false, error: SESSION_EXPIRED\|CHALLENGE\|RATE_LIMITED\|FAILED}`. One request at a time per shop (same lock as sending). `/messages/send` accepts a numeric pk or a username in `username_or_thread`.
Mocked-client tests only, like everything else here.

Error mapping: `FeedbackRequired`, `ChallengeRequired`, `PleaseWaitFewMinutes`, `RateLimitError` (and the other challenge / throttle classes) -> `BLOCKED`
(the backend stops everything and blocks the account for 48 h); `LoginRequired` -> `SESSION_EXPIRED`; `UserNotFound` -> `NOT_FOUND`; anything else -> `FAILED`.

## What is stored

Per shop, one file `/data/<tenant>.json`, mode 0600: instagrapi's settings (session cookies and a stable device fingerprint, so a shop always looks like the same phone).
Passwords and 2FA secrets are never written to disk or to the log here (the backend keeps the login encrypted in its database). Optional per-shop proxy:
`IG_BRIDGE_PROXIES='{"<tenant uuid>": "http://user:pass@host:port"}'`.

## Run

The service is part of the main compose files (`docker-compose.yml`, `docker-compose.test-slim.yml`); no extra override file. Put `IG_BRIDGE_SECRET=$(openssl rand -hex 32)` in `.env`
(the backend gets the same value, plus `META_UNOFFICIAL_ENABLED` default `true` and `IG_BRIDGE_URL=http://ig-bridge:8090`). CI builds and pushes `ghcr.io/m-elnemr/multi_tenant-ig-bridge`.

The service has no published port: only containers on the compose network reach it. Do not expose it to the internet.

## Tests

```bash
cd infra/ig-bridge && python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && pytest -q
```

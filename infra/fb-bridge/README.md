# fb-bridge

A small FastAPI sidecar that sends Messenger messages from a shop's Facebook Page the way a person in a browser would, through the unofficial web endpoints wrapped by
[`fbchat-v2`](https://github.com/m008v/fbchat-v2) 2.3.4 (Apache-2.0, on PyPI). The platform backend decides who, when and how fast; this service only keeps the session, sends and maps errors.
Same layout and conventions as `infra/ig-bridge`.

## Read this first

* **Never run against a real account.** It was written from reading the fbchat-v2 source and tested with a fake transport (`test_app.py`) only. See `docs/unofficial-messenger-spike.md`.
* Meta forbids this kind of automation. A Page or profile used through it can be restricted or banned.
* **Acting as a Page is unproven.** fbchat-v2 is built for personal accounts. We add `i_user=<page_id>` to the cookie string (what the Business Suite web app does) hoping facebook.com
  then serves the Page identity, so `actorID` (which fbchat-v2 uses as sender id) is the Page id. `/sessions/login` checks exactly that and answers
  `{ok:false, error:"FAILED", detail:"PAGE_IDENTITY_NOT_APPLIED"}` when it is not the case, rather than sending as the personal profile.
* Recipients must be numeric ids (`other_user_fbid`). Whether a page-scoped id (PSID) from the official webhook can be addressed this way is unproven.
* Checkpoints cannot be told apart from bad cookies by fbchat-v2 (`dataGetHome` returns nothing for both), so `CHECKPOINT` is only returned if the transport raises it; expect `BAD_CREDENTIALS`.

## API (all but `/health` need the header `X-Bridge-Secret: $FB_BRIDGE_SECRET`)

| Call | Body | Answer |
| --- | --- | --- |
| `GET /health` | - | `{ok:true}` |
| `POST /sessions/login` | `{tenant, cookies, page_id}` (cookies: the pasted `name=value; ...` string, must contain `c_user` and `xs`) | `{ok:true, account}` or `{ok:false, error: BAD_CREDENTIALS\|CHECKPOINT\|FAILED, detail?}` |
| `POST /sessions/logout` | `{tenant}` | `{ok:true}` (deletes the stored session file) |
| `GET /sessions/{tenant}/status` | - | `{exists, logged_in, account}` (`logged_in` = a session is stored; it is only proven valid by the next send) |
| `POST /messages/send` | `{tenant, recipient, text, typing_ms}` | `{result: SENT\|NOT_FOUND\|SESSION_EXPIRED\|BLOCKED\|FAILED, detail?}` |

`typing_ms` is slept before sending (capped at 40 s). Facebook error codes are mapped on a best-effort basis (`EXPIRED_CODES`, `BLOCK_CODES`, `NOT_FOUND_CODES` in `app.py`); unknown ones are `FAILED`.

### Page followers: `GET /facebook/{tenant}/followers?cursor=&collection=&limit=100`

Answer `{ok:true, users:[{id,name,subtitle,vanity}], next_cursor|null, collection}` or `{ok:false, error: SESSION_EXPIRED|RATE_LIMITED|DOCID_STALE|PARSE_EMPTY|FAILED}`.
No cursor: loads `facebook.com/<page id>/followers` (first ~8-10 followers, first cursor, collection id), then pages `POST /api/graphql/` (`ProfileCometAppCollectionListRendererPaginationQuery`, 8 per page, 2-5 s sleep between pages) until `limit` (max 200) is reached or the list ends. Pass the returned `cursor` and `collection` to continue.
`FB_DOCID_FOLLOWERS` (default `28822068060820720`) is the persisted-query id: Facebook rotates it, so when `DOCID_STALE` appears, copy the new `doc_id` of that request from the browser's network tab into the env var and restart.
The graphql answer shape was never captured; the parser is tolerant and only assumes: a follower is any object with a numeric `id` of >= 8 digits, a name (`name` or `title`/`title.text`) and `__typename: "User"` (or a facebook.com profile url); paging is any `page_info`/`pageInfo` object with `end_cursor`/`has_next_page`; the collection id is a string starting `YXBwX2NvbGxlY3Rpb24` (base64 of `app_collection:`). `PARSE_EMPTY` = nothing readable, `DOCID_STALE` = graphql errors or a non-JSON answer. Fixtures in the tests are invented.

## What is stored

Per shop one file `/data/<tenant>.json`, mode 0600: the cookie string (with `i_user`) and the Page id. Cookies are never logged. The `fb_dtsg` token set is kept in memory and rebuilt from the cookies after a restart.
There is no per-shop proxy support: fbchat-v2 does not take a proxy.

## Run

Part of `docker-compose.yml` and `docker-compose.test-slim.yml`; put `FB_BRIDGE_SECRET=$(openssl rand -hex 32)` in `.env` (the backend gets the same value and `FB_BRIDGE_URL=http://fb-bridge:8091`).
No published port. CI (`.github/workflows/bridges.yml`) builds and pushes `ghcr.io/m-elnemr/multi_tenant-fb-bridge`. Needs Python >= 3.10 (the image uses 3.12).

## Tests

```bash
cd infra/fb-bridge && python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && pytest -q
```

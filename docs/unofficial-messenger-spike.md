# Unofficial Messenger sending: spike findings (2026-11-09)

**Nothing here was tested on a real Facebook account.** Every statement below comes from reading the libraries' source and READMEs. The only automated checks are unit tests of
`infra/fb-bridge` against a fake transport. Until someone runs a throw-away account's cookies through it, the Messenger sender is unproven.

## fbchat-v2 2.3.4 (Python, PyPI `fbchat-v2`, Apache-2.0) - the library `infra/fb-bridge` uses

* **Personal accounts only by design.** Built around a browser session of a normal profile. No code path, option or documentation for Pages or Business Suite.
* **Cookie auth.** `dataGetHome(cookie_string)` (`src/_core/_session.py`) GETs `https://www.facebook.com/` with the cookies and scrapes the HTML: `fb_dtsg`, `jazoest`, `sessionId`,
  `client_revision`, and **`FacebookID` from `"actorID":"..."`**. It requires all of them and a numeric actor id, otherwise it returns `None` (it cannot say why: bad cookies and a checkpoint look the same).
* **Sending** (`src/_messaging/_send.py`): POST to the legacy endpoint `https://www.facebook.com/messaging/send/` with a form built by `formAll()` (`__user` and `av` = the actor id,
  `author=fbid:<actor>`), and for a direct message `specific_to_list[0]=fbid:<recipient>` plus `other_user_fbid=<recipient>`. Success = the first element of `payload.actions` has a `message_id`.
  Errors come back as `{"error":1,"payload":{"error-description","error-code"}}`; HTTP errors raise `httpx.HTTPStatusError`.
* **Page identity is unproven.** The library never sets `i_user`. Our idea: add `i_user=<page_id>` to the cookie string (this is how the web Business Suite switches to acting as a Page),
  hoping the home page then reports `actorID = page id`, which flows into `__user`, `av` and `author`. `fb-bridge` checks that `actorID` equals the Page id at login and refuses otherwise
  (`PAGE_IDENTITY_NOT_APPLIED`) so it can never silently send as the personal profile. Whether Facebook then accepts the send, from a plain cookie session, is unknown.
* **PSID addressing is unproven.** The official webhook gives page-scoped ids (PSID). The legacy endpoint takes a global Facebook user id (`fbid:`). Whether a PSID works as `other_user_fbid`
  while acting as the Page is not documented and not shown anywhere in the source. If it does not, existing customers who came through the official channel cannot be reached this way.
* **No follower or comment listing.** There is search, user info, friend/unfriend, posts, marketplace, notifications, thread data, but nothing that lists a Page's followers or the commenters of a Page post.
  The "new customers" feature on Facebook therefore cannot be built on this library (Instagram can, through instagrapi).
* Other facts: needs Python >= 3.10; E2EE support needs a separate Go bridge we do not use; unofficial, no stability promises; the PyPI release 2.3.4 matches the repository's `pyproject.toml`.

## fca-unofficial (Node.js, `VangBanLaNhat/fca-unofficial`) - looked at, not used

* Logs in with `appState` (cookie JSON) or email/password; its own README says to reuse the appState and warns that repeated password logins are a red flag.
* Thread-based: `api.sendMessage(body, threadID)`. Replies are easy; starting a conversation needs a known thread.
* Its README troubleshooting says **`sendMessage` does not work when logged in as a Page**, because Pages cannot start conversations and may only reply within 24 hours of the user's last message
  (the same rule the official API enforces, so it would not help with old customers anyway).
* Node, not Python: using it would mean a third sidecar and a second toolchain for no proven gain.

## What this means for the product

* Instagram (instagrapi) is the realistic unofficial route: it can send by pk or username and can list followers and commenters.
* Messenger through fb-bridge is a best-effort experiment: it either works with the `i_user` trick on a real Page session or it does not. If the live trial fails, the UI should say Messenger sending
  is not available (the backend keeps the "unsupported" sender as the fallback) while everything else ships.
* To verify: take a throw-away Page, log in on a browser, copy the cookie string (must contain `c_user` and `xs`) and the Page id, `POST /sessions/login`, check it is accepted
  (not `PAGE_IDENTITY_NOT_APPLIED`), then `POST /messages/send` to an account you control, first by its global id, then by a PSID taken from a webhook event. Record the three outcomes here.

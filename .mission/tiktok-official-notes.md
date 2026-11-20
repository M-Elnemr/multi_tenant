# TikTok Business Messaging API — notes from the OFFICIAL docs (read 2026-11-20 via business-api.tiktok.com/portal/docs, v1.3)
Sources: Business Messaging API overview; Access to Business Messaging API (id=1832184145137922); Authorization (1832184159540418); Authentication (1832184175482945); Subscribe to Business Messaging Webhook events (1832190670631937).

## Availability / eligibility
- Open Beta in APAC, LATAM, METAP, North America. NOT available for accounts signed up in EEA, Switzerland, UK. US accounts need extra US data security review + USDS addendum. "Rest of World" (Egypt is not in the excluded list) needs only the Data security & privacy review (DSPR).
- Developer must have an approved developer app and pass DSPR (manual, TikTok starts review within 10 working days of the intake form; questionnaire "TikTok/ByteDance Third-Party Due Diligence Questionnaire"; ISO 27001/SOC2/pentest reports speed it up). Progress is not trackable in the portal.
- The TikTok account must be Advanced Access or a Verified Business Account (VBA). General accounts are not eligible. TikTok Shop messages are a different system (not covered).
- Region is the account's sign-up location. The account must accept DMs from everyone (else the owner must accept each request manually before webhooks arrive).

## Authorization (OAuth)
- Developer app needs the "Business Messaging" permission and a logo (512x512 max), and a TikTok account holder redirect URL. Authorization URL shape: https://www.tiktok.com/v2/auth/authorize?{parameters} (copy from My Apps > App Detail > Basic Information > "TikTok account holder authorization URL"); add a unique `state` (CSRF; echoed back). If the user already authorized the same permissions TikTok skips the page and redirects WITHOUT auth_code (append &disable_auto_auth=1 to force it).
- Redirect carries code=<auth_code> (valid 10 minutes, single use).
- POST https://business-api.tiktok.com/open_api/v1.3/tt_user/oauth2/token/ JSON {client_id, client_secret, grant_type:"authorization_code", auth_code, redirect_uri} -> data{access_token(1 day), refresh_token(1 year), open_id (= business_id), scope, expires_in, refresh_token_expires_in, token_type Bearer}.
- POST .../tt_user/oauth2/refresh_token/ JSON {client_id, client_secret, grant_type:"refresh_token", refresh_token}. When the refresh token expires the business must re-authorize.
- GET/POST /tt_user/token_info/get/ returns the scopes authorized.
- Scopes seen in the token response: message.list.manage, message.list.send, message.list.read, user.account.type, user.info.* ...

## Webhooks
- Subscribed ONCE at developer-app level (not per business): POST https://business-api.tiktok.com/open_api/v1.3/business/webhook/update/ JSON {app_id, secret, event_type:"DIRECT_MESSAGE", callback_url} -> data{app_id, callback_url, event_type}. Deliveries are HTTPS POST JSON to the callback.
- Envelope: {client_key, event, create_time(seconds), user_openid (= business_id), content (a JSON STRING)}.
- Events: im_send_msg, im_receive_msg, im_receive_msg_eu (EEA/CH/UK: only {to,to_user,timestamp}, no content), im_referral_msg, im_mark_read_msg, im_auto_message_config_update, im_auto_message_audit_update, im_receive_high_intent_comment.
- im_receive_msg content: from, to, unique_identifier, from_user{id,role}, to_user{id,role}, conversation_id (needed to send), message_id, timestamp(ms), type (text|image|share_post|video|emoji|sticker|reaction|template), text{body}, image{media_id}, is_follower, message_tag{source: APP|WEB|API|OTHERS|UNKNOWN_SOURCE}, referenced_message_info{referenced_message_id}, auto_message_type (when an automatic message), scene_type.
- Our own sent messages come back as im_send_msg (message_tag.source API) — must not be treated as customer messages.

## Sending (official: "Send a message to a conversation", id=1832184403754242)
- POST https://business-api.tiktok.com/open_api/v1.3/business/message/send/ with header Access-Token + Content-Type JSON. Body: {business_id (= open_id), recipient_type:"CONVERSATION", recipient:<conversation_id from the webhook>, message_type: TEXT|IMAGE|SHARE_POST|TEMPLATE|SENDER_ACTION, text:{body (max 6000 chars)}}. A message cannot be text AND image. Reply to a message: referenced_message_info.referenced_message_id (text only). Response data.message.message_id.
- Prohibited: starting a conversation or messaging a user who has not started one. Account must be Advanced Access/VBA and the owner over 18 (check /business/get/ has_advanced_access).
- Images: upload via /business/message/media/upload/ -> media_id (30 days); only where both sides' countries support image attachments; /business/message/capabilities/get/ tells. (We send product pictures as a link in text instead.)
- GET .../business/message/conversation/list/?business_id=&conversation_type=SINGLE|STRANGER&limit=1..100&cursor= (past 90 days, max 100). GET .../business/message/content/list/ (20 latest messages).
## Messaging limits (official page id=1854823717597185) for non-mutual-follow conversations
- After the first user message: up to 10 messages within 48 h. When the user replies a new window starts: unlimited for 48 h after each last reply. More than 48 h after the user's last reply: max 3 more messages until the user replies again.
## Webhook verification (official id=1759978341579777)
- Header `Tiktok-Signature: t=<unix seconds>,s=<hex>`; s = hex(HMAC-SHA256(app secret, t + "." + body)); reject if the timestamp is too old (official sample accepts 5 s). Respond 200 on success, 4xx on failure.
## Return codes (official "Return codes")
40001 no permission; 40002 parameter error; 40007 object does not exist; 40100 requests too frequent (rate limit); 40105 invalid/incorrect access token; 40908 unsupported file type; 40064 message blocked by direct message rules; 51065 system error (retry).
## Other
- tiktok.me/{username}?ref=..&message=.. short links start conversations (ref/prefill only on the mobile app). Direct Messaging Ads also start conversations.
- Comment-to-Message only for TikTok accounts registered in Vietnam, Indonesia, Thailand (not Egypt).
- Not read/unverified: /business/get/ response schema; revoke endpoint (none documented in the pages read); app-level rate limit numbers.

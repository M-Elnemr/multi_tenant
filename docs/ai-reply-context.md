# Reply to a specific message (WhatsApp-style) and reply-aware AI

Status legend: **Implemented** (code exists) · **Tested (mocked)** (automated tests with a scripted assistant and faked platforms) · **Browser-checked** (driven in a real Chrome against a local backend with a stub model) · **Not validated** (needs a real model or real platforms).

## What was built
1. **Swipe to reply on the website**, in two places: the shop team's inbox (`components/inbox`) and the customer's storefront chat widget (`components/shop/live-chat.tsx`). Touch/pen swipe toward the end of the line (rightwards in LTR, leftwards in RTL/Arabic); a reply button on every message (hover or keyboard focus, no touch needed); Escape or the X cancels. The composer shows who and what is being replied to; the sent bubble shows the quote; clicking the quote jumps to the original (the inbox loads older pages first when the original is not loaded yet).
2. **A reply data model** (migration `V93__message_replies.sql`), validated against tenant and conversation.
3. **Reply-aware context for the assistant**: the backend reads the exact quoted message by its validated reference (however old) and tells the model which message the customer is answering.
4. **The same for WhatsApp (Cloud and own number), Messenger and Instagram**, where the platform tells us which message was quoted.

## Data model
`chat.messages` gains `reply_to_id` (FK to the same table, `ON DELETE SET NULL`, partial index), `reply_to_mid` (the platform's id of the quoted message) and `reply_quote` (JSON snapshot: sender, kind, text up to 600 chars, hasMedia, time, source). New table `chat.message_mids (tenant_id, conversation_id, provider_mid, message_id, part_text)` records the platform's id of every message we sent (each bubble, with its exact text) and of each customer line received, so a native reply (which carries only an id) finds what it quotes. No existing row changes; all additions are nullable. Rollback: `docs/rollback/V93_message_replies_rollback.sql`. **Tested** on a copy of an older database (schema 88, 1306 messages): migrate, re-run (idempotent), rollback, re-migrate; existing rows untouched.

## API
- Website customer: `POST /api/v1/shop/chat` accepts `replyToMessageId`; the answer returns `messageId` and `userMessageId`; `GET /api/v1/shop/chat/history` returns each message's `id` and, for a reply, `replyTo {id, sender, text, hasMedia, available}`.
- Shop team: `POST /api/v1/store/chat/conversations/{id}/reply` accepts `replyToMessageId`; conversation messages carry `replyTo` (also on older pages, `?before=`).
- Owner test chat: `POST /api/v1/store/ai/test` accepts `replyToMessageId` (used by the evaluation suite).
- A reference that is not a message of this conversation and shop is refused with `400 INVALID_REPLY_REFERENCE` **before anything is saved**, with a message that reveals nothing about other chats or shops.

## Validation and isolation
Every read and write names tenant **and** conversation (`MessageReplies`). A platform id is looked up only within the same conversation and tenant. A client-supplied id is never trusted. A model cannot reference messages: it never sees ids. **Tested (mocked)**: another shop's message, another chat of the same shop, a nonexistent id, and the same platform id in another shop all resolve to nothing, and nothing is saved on refusal.

## Reply-aware AI context
For a message that replies to another, `ChatService` appends a block to the **end** of the system prompt (after the cache break, so the stable prefix and its cache hits are unchanged) with the labels MESSAGE BEING REPLIED TO, ORIGINAL MESSAGE SENDER, SENT, ORIGINAL MESSAGE CONTENT, PRODUCTS NAMED IN IT, RELEVANT CONVERSATION CONTEXT (two messages either side, only when the original is older than the history window), CUSTOMER'S LATEST MESSAGE, and rules: understand the latest message against the original first; the original is OLD, so prices, stock and offers must be re-checked with the tools (the tools and ORDER ON FILE are the CURRENT BUSINESS STATE); never invent what the original does not say; ask one short question if it does not identify the item; never create or change an order twice.
It is in the system prompt, **not** in the customer's turn, on purpose: the code that checks quantities, prices and names reads the customer's words, and a quoted old price must not be mistaken for something the customer just said (**tested**: the customer turn is unchanged).
Old messages: the quoted message is read by primary key (**0.07 ms** on a chat of 3000+ messages, index scan, measured; the model call is seconds), independent of the 24-message history window (**tested**: original is outside the window, in the prompt with its neighbours). If it was deleted, the stored snapshot is used and marked as such. If it cannot be found, the assistant is told it is unavailable and not to guess (**tested**). The block costs about 1.5k characters of fixed instructions plus the quote, only on turns that are replies, and no model call.
Retrieval: a vague question asked in reply to a message about a product keeps that product's name in the `shop_knowledge` query (unit-tested; **retrieval quality not measured**).
Memory/summarization: this system does not summarize history; memory is short notes per customer, and the window is the last 24 messages. Reply links are structured columns resolved from the database every turn, so they do not depend on either. Facts stated before the window and not saved as notes are still lost (probe case 921 in the evaluation suite measures this; not yet run).

## Platforms
| Platform | What the platform sends | What we do |
|---|---|---|
| WhatsApp Cloud | `context.id` (id only, no text) | resolve in `message_mids` (exact bubble), else "unavailable" |
| WhatsApp own number (Evolution) | `contextInfo.stanzaId` + `quotedMessage` (content included) | stored message by id wins; else the platform's own quote, marked as such |
| Messenger / Instagram | `reply_to.mid` (id only); Instagram story replies: `reply_to.story` | resolve by id; a story reply is described as a story, nothing invented |
**Tested (mocked platforms)**: signed webhooks and parsers for all four, including bursts of lines (`InboundBurst` carries each line's id and reference). **Not validated** against real WhatsApp/Meta/Evolution traffic.
Outbound: **no native reply/quote is sent to any platform** (not implemented; no claim is made). The website's reply works independently of platform capabilities. A shop-team reply made in the inbox is stored with its reference and shown in the inbox; on WhatsApp the customer receives the text without a quote.

## Browser check (real Chrome, local backend, stub model, local test shops)
Touch-type pointer events driven in the real DOM: mouse ignored; vertical scroll cancels; wrong direction cancels; 30 px moves the bubble but does not reply; 80 px replies; the preview appears; sending stores `reply_to_id`; the quote is still there after a full reload; Escape and X cancel; the reply button is keyboard-reachable; clicking a quote scrolls to and marks the original; in the inbox a quote whose original is on an unloaded older page loads pages until it is there. The model was a local stub, which replied differently when the prompt contained the "MESSAGE BEING REPLIED TO" block: proof the block reaches the model through the real backend, **not** proof a real model understands it. Real touch hardware and screen readers were not tested.

## Known limitations
- Only the first reply reference of a burst of customer lines is stored on the row; all of them are used for that turn's context.
- Owner messages sent to Messenger/Instagram from the inbox do not record the platform id (the sender returns no id), so a customer's native reply to such a message is "not available".
- The customer widget loads the last 60 messages, so it cannot jump to an older original (the quote still shows).
- A reply sent together with a photo or voice note (media endpoint) does not carry the reference.
- Customer photos and voice notes are stored as the text the assistant read, so quoting them shows that text.
- A live-model evaluation of reply understanding has not been run (see ai-evaluation-strategy.md).

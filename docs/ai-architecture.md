# AI architecture (as of 2026-11-15)

## Request lifecycle
customer message -> `ChatService.reply` (`ReplyTimeline.begin`, `AiTelemetry.beginReply`) -> conversation + pause/points checks -> history (24 messages) -> `systemPrompt` -> `modelLoop` (GLM via `OpenAiProvider`, tools: search, product details, knowledge, prepare/place order, flag for owner) -> `reviewed` -> `ruleChecks` (facts, money, quantities, variants, sizes, summary shape) -> polish -> save -> channel send. Spans of every model call and the whole reply go to Langfuse when enabled.

## Prompt layout (cache-friendly)
Stable first: persona, RULES, playbook, max discount, shop facts, catalog, lessons. Then `CACHE_BREAK`, then dynamic: open hours, customer memory, address/identity rules, saved details, order on file, open questions. Prefix stability is what lets Hive's automatic prompt cache hit; cached tokens are read from `usage.prompt_tokens_details.cached_tokens` and shown in usage reports and Langfuse (`cache_read_input_tokens`).

## Memory and context
History is the last N messages (N=`AI_HISTORY`, default 24, max 40). Durable facts live outside the window: `ORDER ON FILE`, `SAVED DETAILS`, `WHAT YOU REMEMBER ABOUT THIS CUSTOMER` (seller memory), `open questions`, conversation `pending` state. Code-side guards (chosen variant, quantity) re-read the customer's earlier messages for the order, not only the window.

## RAG
`KnowledgeIndex`: product and shop texts chunked per tenant, embedded, searched by meaning; tools return variants with exact per-variant stock. Measured separately with `backend/scripts/rag_eval.py` (top-1/3/6 on labelled questions + negatives). Cross-tenant: all SQL filters `tenant_id`; vector cache keyed by tenant.

## Streaming: deliberately not added
Replies are rewritten after generation by price/stock/quantity/variant guards, a review pass and fallbacks, and "order placed" text is produced by code. Streaming raw model tokens would show unverified facts, which the brief forbids. WhatsApp, Messenger and Instagram have no token streaming anyway; the website widget could stream only a verified final answer, which is no earlier than today. Latency work belongs to call count and endpoint speed.

## Observability
`ReplyTimeline` (logs), `AiUsageService` (SQL), Micrometer, and the new `AiTelemetry` (Langfuse over OTLP/HTTP JSON): non-blocking bounded queue, daemon sender, metadata only by default, salted-hash tenant/chat ids, redaction when content capture is on. Failure of Langfuse cannot affect a reply (unit-tested).


## Provider reliability: GLM (Hive) first, Alibaba Qwen per-request fallback (round 2)
**Primary:** GLM `zai-org/glm-5.3-flash` through Hive (`https://api-cdn.thehive.ai/api/v3`). The Hive key is the env var named `GLM_API_KEY` in this repo (`app.ai.glm.api-key`, compose pass-through): it *is* the Hive credential, not a separate GLM account, and more Hive keys can be added encrypted in the admin AI screen (`HiveKeyPool`). Nothing was renamed or introduced.
**Fallback:** Alibaba Qwen (`qwen3.7-flash`, DashScope OpenAI-compatible endpoint, key `QWEN_API_KEY`) already existed as GLM's backup (`ChannelAi.backupFor`). This round fixed *when* it is used.

Every new request calls GLM first. If that request fails, Qwen answers it immediately; the failed response is discarded and never shown.

| Detected (in `OpenAiProvider` + `ProviderFailure`) | Category |
|---|---|
| HTTP 429 | RATE_LIMIT |
| HTTP 408 / request timeout | TIMEOUT |
| HTTP 5xx | SERVER_ERROR |
| connection failure | CONNECTION |
| HTTP 200 whose body has an `error` field, a code/msg envelope, no choices, or finish_reason `error` / `insufficient_system_resource` / `overloaded` | PROVIDER_ERROR |
| HTTP 200 with unreadable JSON | MALFORMED (never retried) |
| empty answer (no text, no tool call), also after one retry | EMPTY |
| HTTP 200 whose *content* is a short provider capacity notice (en / Egyptian and MSA Arabic / zh) | OVERLOAD_MESSAGE |

Text detection is deliberately narrow: only an answer with no tool call, at most 350 characters, that names a service subject AND a busy/unavailable state (or is a bare retry-later instruction, short and without figures). Refusals, «استنى دقيقة هشوفلك», «المحل عليه ضغط طلبات», `finish_reason=content_filter` are never failures (tested). A false positive costs one extra Qwen call, never a wrong fact. If Qwen also fails, or also answers with a capacity notice, the customer gets the existing polite `unavailableText`, never provider text.

**Timing:** through Hive there is no sleeping or retry loop any more. A 429/408 goes once, immediately, on another key only if the pool has another; everything else goes straight to Qwen. (Before: up to 2 retries with 1 s + 2.5 s pauses on GLM first. Intentional change, requested.)
**Why there is no circuit breaker:** no state remembers a failure across requests. The existing per-key 30 s rest in `HiveKeyPool` only changes which key is lent; `acquire()` still lends a resting key when it is the only one, so GLM is attempted on every request (test `everyNewRequestTriesGlmFirst...`: five failures in a row, the sixth request still goes to GLM first).
**Side-effect safety:** if the failed attempt already did something non-repeatable (`madeChanges`: summary shown, order placed/cancelled, owner flagged), the reply is NOT restarted on Qwen; the persisted state decides and code writes the confirmation. `place_order` also reads and clears the conversation's persisted `pending`, so a replay cannot create a second order.
**Observability:** the failure category is in the log, in `ai events` (ERROR / FALLBACK rows) and in Langfuse (ERROR generation + `provider-fallback:glm->qwen:CATEGORY` event). Existing `ai_usage_log` keeps per-model tokens and the fallback flag.


## Reply to a specific message (round 3)
Request lifecycle addition: after the customer's message is stored, a reply reference (our id from the website, or the platform's id/quote from WhatsApp, Messenger, Instagram) is validated against tenant and conversation and saved (`reply_to_id`, `reply_to_mid`, `reply_quote`); the quoted message is read by primary key and written as a labelled "MESSAGE BEING REPLIED TO" block at the end of the system prompt (after the cache break). Platform message ids of everything we send and receive go to `chat.message_mids`. Details, API, limits: `docs/ai-reply-context.md`.

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

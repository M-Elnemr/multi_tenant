# AI performance audit (2026-11-15)

Scope: the customer-facing sales assistant (`backend/src/main/java/com/platform/chat`). Read from the code and config, not from old notes.

## Current setup (verified in the repo)
- **Primary model: GLM** `zai-org/glm-5.3-flash` through Hive (`https://api-cdn.thehive.ai/api/v3`), reasoning `low`, 5 req/s throttle, Hive key pool (`app.ai.glm.*`, `AI_DEFAULT_TEXT_MODEL=glm`). Fallback/alternatives (Qwen, Claude Haiku, DeepSeek) are selected by `ChannelAi` / the admin AI screen. **Nothing about model, provider, routing or fallback was changed in this work.**
- Reply path (`ChatService.replyTimed`): save message -> history (last 24, `AI_HISTORY`) -> system prompt (stable rules + catalog first, `AiProvider.CACHE_BREAK`, then per-chat facts: open hours, memory, address rule, order on file, open questions) -> model loop with tools (max rounds) -> review -> rule checks (`FactCheck`, `MoneyGuard`, `ReplyGuard`, ...) -> polish -> save/send.
- Resilience: `OpenAiProvider` retries (3 attempts, key rotation), 20 s call cap, hedged twin (`AI_HEDGE_MS`), per-reply time budget (`AiBudget`, 45 s), throttle.
- Retrieval: tenant-scoped embeddings (`KnowledgeIndex`, Alibaba embeddings, in-memory vectors keyed by tenant). Every query filters `tenant_id`.
- Observability that already existed: `ReplyTimeline` (per-phase and per-call ms, one log line per reply), `AiUsageService` (tokens/cost per request in `ai_usage_log`), Micrometer/Prometheus, `AiHealthController`, eval harness (`AiEvalService`).
- Transactional safety is code, not model: price/stock/variant/quantity/order validation and "order placed" text come from backend results.

## Baseline
| Measure | Value | Source / validity |
|---|---|---|
| Backend test suite | 722 tests, 5 skipped, 0 failures (8 min 22 s) | run 2026-11-15 on unmodified code, `cleanTest test` |
| GLM live quality (43 conversations, 4 shops) | 15/15 orders correct, stock statements all correct, 0 fallbacks, judge 71% (own-shop scenarios) | `docs/glm-test-2026-11-12/REPORT.md`, live, 1 trial |
| GLM latency | median 6.5 s, P90 20.3 s (n=78 replies) | same report |
| GLM cost | about $0.0013 per conversation | same report |
| Earlier latency study | measured with DeepSeek as primary, **not** a GLM baseline | `docs/latency-2026-11-11` |

**Not measurable here:** TTFT, provider cache hit rate, current retry/timeout rates, hallucination rate, any new GLM latency or quality number. There is no `GLM_API_KEY` in this environment. No metric in this work was estimated and presented as measured.

## Weaknesses and bottlenecks found
1. No external trace/experiment tooling: timelines only in logs, usage only in SQL. (Addressed: Langfuse export.)
2. No dataset-driven regression suite runnable in CI against the real reply path with deterministic assertions beyond `ai_eval.py` hard checks. (Addressed: Promptfoo suite.)
3. Latency is dominated by model calls (about 2.3 per reply, 4-5 s each) and the 12-20% slow tail, per the earlier study; code and DB time are milliseconds. Real gains need either fewer calls (needs A/B with the order checks) or a faster endpoint, not backend tuning.
4. Streaming is not possible on the main channels and not safe for the main flow (see ai-architecture.md).


## Round 2 measurements and findings
**Code-side overhead with an instant model** (`ReplyOverheadBenchmarkTest`, local Postgres, synthetic shops, one run, 3-4 replies each; not production numbers):
| Shop | total | prompt build | search tool | rule checks |
|---|---|---|---|---|
| 12 products | 376-388 ms | 125-133 ms | 110-114 ms | 59-64 ms |
| 40 products | 919-983 ms | 412-421 ms | 229-234 ms | 185-188 ms |
Prompt building grows about 10 ms per product for shops up to 60 products: `allProducts` -> `productLine` runs `compactVariants` (a catalog detail read) and `productDetails` (a query) per product, every reply (an N+1). The 2026-11-11 production timeline measured the prompt at about 70 ms against a model call median of 4.7 s, so the realistic gain from batching is under 1% of reply time while it touches the shared catalog service. **Left unchanged on purpose; recorded as a deferred, low-value optimisation.** Stock lines must never be cached, which rules out the easy fix.
**New failure-check cost:** `ProviderFailure.overloadMessage` 17 us typical, 58 us for a 340-character answer (`ProviderFailureBenchmarkTest`).
**Quality risk found by reading the code:** the model sees only the last 24 messages (`AI_HISTORY`, max 40). A fact stated earlier survives only via saved details / seller memory / pending order. Probe case 921 tests this live; no measurement exists yet.
**Provider reliability gap found and fixed (round 2):** HTTP-200 capacity notices were delivered to customers (no check existed); a 429 or 5xx on GLM cost up to 3.5 s of sleeps plus extra attempts before Qwen was tried; a malformed 200 body was retried; a reply that failed after GLM had placed an order was restarted on Qwen with a stale prompt.

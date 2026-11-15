# AI performance results (2026-11-15)

## Honest summary
This pass added **observability and evaluation tooling**. It did **not** change prompts, memory, retrieval, caching, concurrency or any model/provider setting, and therefore **makes no speed, quality or cost claim**. No GLM key was available, so no new live measurement was possible.

## Implemented (completed)
- `AiTelemetry` / `AiTelemetryConfig`: optional Langfuse export over OTLP/HTTP JSON; off by default; non-blocking; hashed ids; redaction; wired into `ChatService` (reply start), `ReplyTimeline` (reply end), `OpenAiProvider` (every model attempt, success or failure). Unit-tested (4 tests).
- Promptfoo suite (`backend/evals/promptfoo`): 95 cases, deterministic assertions, offline self-test (12 checks, pass).
- Config: `application.properties`, `.env.example`, `docker-compose.test-slim.yml` pass-through (server `.env` not touched).
- Docs: audit, architecture, evaluation strategy, operations guide, tool decisions.

## Test results
| Run | Tests | Skipped | Failures |
|---|---|---|---|
| Baseline, unmodified code | 722 | 5 | 0 |
| After changes (full `cleanTest test`, 8 min 40 s) | 726 (722 + 4 new) | 5 | 0 |

## Before/after
| Metric | Before | After |
|---|---|---|
| Quality, latency, tokens, cost, cache hits | GLM live report 2026-11-12 (orders 15/15, median 6.5 s, P90 20.3 s, about $0.0013/conversation) | **not re-measured** (no key) |
No improvement is claimed.

## Partially completed / blocked / future
- Partial: Langfuse tool/review/check spans (`AiTelemetry.step` exists, unused); exporter counters not exposed as metrics.
- Blocked (needs GLM key, running backend, Docker): Promptfoo end-to-end run, live latency/TTFT/cache benchmark, real Langfuse server check, Ragas/DSPy evaluation.
- Future: A/B of fewer model rounds (selective review) with order checks as gate; faster GLM endpoint; GlitchTip if exception alerting is wanted.
- Security review: retrieval is tenant-filtered in every query; prompt-injection and cross-shop probes added to the Promptfoo suite but **not yet run live**.


## Round 2 (2026-11-15)
**Test results:** full `cleanTest test` 751 tests, 5 skipped, 0 failures (9 min 48 s) = 726 from round 1 + 25 new (ProviderFailureTest 5, GlmAlibabaFallbackTest 14, ProviderFallbackOrderIntegrationTest 3, KnowledgeTenantIsolationIntegrationTest 1, ProviderFailureBenchmarkTest 1, AiTelemetryTest +1). No existing test was changed or weakened; the retry change did not break any of them.

| Status | Item |
|---|---|
| **Implemented + locally tested (mocked services)** | GLM-first / Alibaba-per-request fallback with failure detection (429, 5xx, timeout, connection, empty, malformed, body error, HTTP-200 capacity message in en/Egyptian-MSA ar/zh); capacity text never delivered; no GLM retry sleeps; no circuit breaker (tested); order-safe restart guard; failure categories in logs/events/Langfuse; cross-shop retrieval isolation test |
| **Measured** | failure check 17 us typical / 58 us worst case; code-side reply overhead 376 ms (12 products) to 983 ms (40 products) with an instant model, local only |
| **Implemented, not yet run live** | 107-case Promptfoo suite (87 golden + 20 extra incl. 12 awareness cases and one history-window probe); assertion self-test 26 checks passes |
| **Unverified** | anything against real Hive GLM or real Alibaba (no credentials on this machine; production keys must not be used); real Langfuse delivery (no Docker); fallback success rate and latency on real providers; context-recall rates |
| **Deliberately not done** | prompt/memory/RAG changes (no way to measure them); batching the prompt-build N+1 (measured, under 1% of reply time, shared service); streaming; new infrastructure (Typesense, Meilisearch, Temporal, Activepieces, PostHog, Metabase) |

**Before/after:** latency, quality and cost on real traffic are **not re-measured**; the only valid GLM baseline remains 2026-11-12 (orders 15/15, median 6.5 s, P90 20.3 s). Behavioural changes that should help the tail (no 3.5 s of GLM sleeps before Qwen, no retries of malformed 200s, capacity notices no longer reach customers) are proven by tests to happen; their effect in seconds on production is **not measured**.

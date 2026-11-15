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


## Round 3 (reply to a specific message)
See `docs/ai-reply-context.md` for the feature, tests and limits.

| Check | Result |
|---|---|
| Backend full suite (`cleanTest test`, 34 min on a busy machine) | **770 tests, 5 skipped, 0 failures** = 751 before + 19 new (ReplyReferenceIntegrationTest 7, ReplyChannelsIntegrationTest 3, ReplyUnitTest 9) |
| Provider fallback tests (GLM first, Alibaba fallback, no circuit breaker, no duplicated order) | all still pass (GlmAlibabaFallbackTest 14, ProviderFallbackOrderIntegrationTest 3, ProviderFailureTest 5) |
| Web: `tsc`, `eslint --quiet`, i18n check, `npm test` | clean / clean / ok / 12 of 12 |
| Promptfoo assertion self-test | 37 checks pass |
| Migration V93 + rollback on a copy of an older database (1306 messages) | migrate, re-run, rollback, re-migrate all ok, messages untouched |
| Browser check (real Chrome, local backend, stub model) | swipe/reply/cancel/quote/reload/jump behave as designed (see ai-reply-context.md) |
| Quoted-message read | 0.07 ms on a 3000+ message chat (index scan) |
| **Not validated** | a real model's understanding of replies (no credentials; 117-case Promptfoo suite not run), real WhatsApp/Meta/Evolution webhooks, real touch hardware, screen readers, retrieval quality |


## Round 4 (reply limitations and awareness)
| Item | Status |
|---|---|
| Per-line reply references in bursts (V94), FB/IG outbound ids, widget `around` navigation, WhatsApp Cloud quoted replies with plain fallback, earlier-messages digest, old-quoted-price guard fix | Implemented; **tested with mocked platforms and a scripted assistant** |
| Messenger / Instagram / own-number WhatsApp native quoting | **Not implemented** (no verifiable official parameter / unofficial gateway) |
| Quote the real WhatsApp Cloud API accepts our `context.message_id` | **Not validated** (docs confirm the field; no live call) |
| Live-model understanding of replies, digest and block | **Pending** (no credentials; 121-case suite not run) |
| Widget navigation in a real browser | **Pending** for this change |
Test counts are in the final table below.


### Round 4 test results
| Check | Result |
|---|---|
| Backend full suite (`cleanTest test`, 20 min on a busy machine) | **786 tests, 5 skipped, 0 failures** = 770 before + 16 new (ReplyPhase2IntegrationTest 14, ReplyUnitTest +2) |
| Provider fallback tests (GLM first, Alibaba fallback, no breaker, no duplicated order) | still pass |
| Web: `tsc`, `eslint --quiet`, i18n, `npm test` | clean / clean / ok / 13 of 13 |
| Promptfoo assertion self-test | 43 checks pass; suite is 121 cases, **not run live** |
| Migrations V93 + V94 and both rollbacks on a copy of an older database (1306 messages) | migrate, re-run, rollback, re-migrate all ok |
| Reply-path reads (4000+ message chat, busy machine) | quoted read 0.40 ms, around 0.80 ms, digest 8.6 ms |
| Not validated | live model behaviour; real WhatsApp/Meta/Evolution traffic including the quoted send; the widget navigation in a browser |


## Final phase (memory, retrieval, prompt safety, payment guard)
| Check | Result |
|---|---|
| Backend full suite (`cleanTest test`, 25 min on a busy machine) | **811 tests, 5 skipped (Redis, S3, scale volume: environment-dependent, same as before), 0 failures** = 786 before + 25 new |
| A first full run of this phase had 2 failures caused by my own change (the money check's lookup of the order block matched the words inside the rules and allowed an invented urgency claim); fixed, a real-order payment test added, then the whole suite rerun from scratch | see the row above |
| Provider fallback (GLM first, Alibaba fallback, no breaker, no duplicated order) | all still pass (GlmAlibabaFallbackTest 14, ProviderFailureTest 5, ProviderFallbackOrderIntegrationTest 3); no provider file or configuration was touched |
| New tests | MemoryFactsExtractionTest 7, MemoryFactsIntegrationTest 9, RetrievalBenchmarkTest 2, KnowledgeRetrievalIntegrationTest 2, PaymentClaimGuardTest 2, PaymentClaimIntegrationTest 1, ProductDetailsBatchTest 1, ReplyPerfProfileTest 1 |
| Web: `tsc`, `eslint --quiet`, i18n, `npm test` | clean / clean / ok / 13 of 13 (no web file changed in this phase) |
| Promptfoo | 143 cases; assertion self-test 66 checks pass; **not run against a live model** |
| Browser (real Chrome, disposable DB, stub model) | widget old-message navigation, deleted original, snapshot after reload, phone-width emulation, touch gestures: see ai-reply-context.md |
| Migrations V93+V94+V95 and rollbacks on a copy of an older schema (1306 messages) | migrate, constraints enforced, re-run, rollback of all three, re-migrate: all ok |
| Retrieval benchmark (synthetic, mechanism only) | Recall@5 .49 -> .81 (normalising word search), hybrid .95 on a stand-in vector; see ai-memory-and-rag.md |
| Performance | statements per reply -10% for a 60-product shop (569 -> 510); latency differences within noise on a loaded machine; see ai-performance-audit.md |
| **Not validated** | any live-model behaviour (memory use, retrieval with the production embedding, prompt rules, Egyptian-Arabic quality); real WhatsApp/Meta traffic; real touch hardware and screen readers; hybrid retrieval and any embedding candidate on real data |

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

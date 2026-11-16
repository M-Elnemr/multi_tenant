# Reply performance instrumentation (Phase 1)

Measurement only. It changes no prompt, model, timeout, retry, hedge, fallback, budget, review, burst window, pool size or schedule. Off with the environment variable `AI_TRACE=false` (default on). It binds to the Spring property `app.ai.trace.enabled` (`application.properties`: `app.ai.trace.enabled=${AI_TRACE:true}`); `docker-compose.test-slim.yml` passes `AI_TRACE` into the backend container, so put `AI_TRACE=false` in the server `.env` and recreate the backend. When off: no trace is created, nothing is queued or written, no writer thread starts, and no `elmanassa.*` metric is recorded; replies and all other metrics are unchanged. Use only this name.

## What it is
- `ReplyTrace` (one per inbound customer message, extends `ReplyTimeline`, which keeps its log line), `ReplyMetrics` (Micrometer, global registry, Prometheus at `/actuator/prometheus`), `ReplyTraceStore` (bounded queue + one daemon writer thread, one row per reply in `chat.reply_traces`, 60-day retention). Migration `V96__reply_traces.sql`; rollback `docs/reply-traces-rollback.sql`.
- Why a new table: `chat.ai_events` has one short text and one number per event and no trace id, and no failed model call is in it.
- Durations come from `System.nanoTime()`; each event has `t` (ms since the first webhook line arrived) and, for spans, `d`. A span `x` means `x_started` at `t` and `x_completed` at `t+d`.
- Privacy: events carry only numbers, booleans and identifier-like strings (letters, digits, `_ : . -`, never 6 digits in a row); anything else is stored as `?`. No prompt, message, answer, tool argument, phone number, header or key can enter, because callers pass categories.

## Event names
| Stage | Events (span unless noted) |
|---|---|
| Inbound | `webhook_received` (instant), `burst_wait`, `queue_wait`, `processing_started` (instant) |
| Context | `context_load`, `memory_load`, `rag`, `prompt_build`, `prompt_ready` (instant) |
| Provider | `provider_request_started` (instant), `provider_response_completed` / `provider_request_failed` / `provider_request_timed_out`, `provider_attempt_hedged` (instant), `provider_hedge_request` (parallel), `provider_call` (one per model call, never nests) |
| Fallback | `fallback_started` (instant, `waited_ms` = what the failed model cost), `fallback_completed` |
| Tools | `tool` (`tool`, `ok`, `round`, `next_model_call`) |
| Checks | `rule_checks` (`problems`, `kinds`, `rewrites`, `deterministic_ms`, `rewrite_ms`, `accepted_original`), `rewrite` (`reason`) |
| Review | `review` (`verdict`: approved, corrected, kept_original, timeout, error, unknown, skipped; `detail`, `differs`, tokens) |
| Outbound | `outbound_completed` / `outbound_failed` (`channel`, `via`, `kind`, `status` accepted or failed, `confirmed` always false) |
| End | `reply_completed` / `reply_failed`, plus `note` (`ai_unavailable`) |

`first_token_received` is not recorded: the provider calls are not streamed.

## Lifecycles
- Normal GLM: `webhook_received > processing_started > context_load > prompt_build > memory_load > rag > prompt_ready > provider_request_started > provider_response_completed > provider_call > review > rule_checks > reply_completed > outbound_completed`.
- GLM timeout then Qwen: `... provider_request_started(glm) > [provider_attempt_hedged > provider_hedge_request x2] > provider_request_timed_out > provider_call(glm, failed, d = the whole stall) > fallback_started(waited_ms) > provider_request_started(qwen, role fallback) > provider_response_completed > provider_call(qwen) > fallback_completed ...`.
- Hedge: `provider_attempt_hedged(after_ms)`, then two `provider_hedge_request` (`which` first/twin, `winner`, `parallel`, `billed`); the call waits for the winner. The loser is not cancelled (existing behaviour); a loser that ends after the reply was saved reaches only `elmanassa.provider.hedge_losers`.
- Tool + rewrite: `provider_call > tool > provider_call > rule_checks{ rewrite{ provider_call ... } }`.
- Outbound failure: `outbound_failed(error = exception class)`; the reply is already saved and the trace shows it.

## Correlation
One opaque id per reply: `chat.reply_traces.trace_id` (a random UUID, no customer data). The same id is (1) in the log lines `Reply trace: id=<id>`, `Reply timeline: ... trace=<id>` and `Reply timing: ... trace=<id>`, and (2) the Langfuse trace id when `AiTelemetry` is on (the 32 hex characters of the UUID without dashes; `AiTelemetry.beginReply(..., ReplyTrace.currentHexId())`). `chat.ai_events` rows carry no trace id (unchanged): join them to a trace by `conversation_id` and time. The id is created when the burst holder's worker (or the web request) starts the reply and follows it through hedge threads, fallback and the channel's outbound sends. A messaging reply (WhatsApp, Messenger, Instagram) stays open after the model flow until the channel's `respond()` finishes (with the burst holder's worker as a backstop, and the next reply on the thread as a last resort), so its outbound events are in the same trace; website and test replies are complete when the model flow ends.

## Retention
`ReplyTraceStore.purge()` runs daily at 04:50 Cairo on the scheduler thread: it deletes traces older than 60 days in batches of 5000 (by the `started_at` index, oldest first), at most 40 batches per run with a 100 ms pause between batches, and a failure only ends the run (logged once a minute). It is independent of `AI_TRACE`.

## Privacy (allowlist)
A trace can only hold the event names and attribute names listed in `ReplyTrace` (`EVENTS`, `SPECS`). Numbers are small non-negative integers under a per-name cap (at most 10^7); strings are an enumerated value or a narrow identifier pattern with at most 6 digits in all; exception messages are never stored, only the class name. The `tenant_id` and `conversation_id` columns exist for isolation and cascade deletion (like `chat.ai_events`); no event or summary contains them.

## Not double counted
`summary.model_ms` sums only `provider_call` spans (never nested, never parallel); hedge requests are children flagged `parallel`. `review`, `rewrite` and `rule_checks` spans contain their model calls: use `model_ms_by_role_purpose` for model time and `rule_checks_deterministic_ms` for code time.

## Baseline queries (read-only)
```sql
-- end-to-end percentiles per channel, customer traffic only
SELECT channel, count(*), percentile_cont(ARRAY[.5,.9,.95,.99]) WITHIN GROUP (ORDER BY end_to_end_ms) FROM chat.reply_traces WHERE source='REPLY' AND started_at > now() - interval '7 days' GROUP BY 1;
-- what a failed model call cost before the fallback
SELECT percentile_cont(.95) WITHIN GROUP (ORDER BY (summary->>'failed_model_ms')::int), count(*) FILTER (WHERE (summary->>'fallbacks')::int > 0) FROM chat.reply_traces WHERE source='REPLY';
-- review verdicts
SELECT summary->>'review_verdict' v, count(*), avg((summary->>'review_ms')::int) FROM chat.reply_traces WHERE source='REPLY' GROUP BY 1;
-- evaluation tokens (never in chat.ai_usage_log)
SELECT date_trunc('day', started_at), sum((summary->>'tokens_in')::bigint), sum((summary->>'tokens_out')::bigint) FROM chat.reply_traces WHERE source='EVAL' GROUP BY 1;
```


Operator guide with inspection queries, slow-GLM investigation and the rollout plan: `docs/ai-reply-quality-and-tracing.md`.

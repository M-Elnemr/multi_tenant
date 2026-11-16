# AI reply quality and reply tracing — operator guide

This describes what is implemented in the working tree (HEAD `66c5002` plus the uncommitted work listed in the commit plan). Nothing here changes the AI provider, model, prompt, timeout, retry, hedge, fallback or review behaviour: GLM (through Hive) stays primary, Qwen (DashScope) stays the fallback.

## 1. FactCheck (reply facts against the shop's own data)
`FactCheck` runs on every model draft inside `ChatService.ruleChecks` (and its fixers). It reads the draft, finds which of the shop's products it talks about, and compares what it says (colours, sizes, option prices, stock, hours, delivery areas) with the shop data; a wrong fact is either put right by code (`FactCheck.fix`) or sent back to the model once with a `[SYSTEM CHECK]` note (rule kinds such as `INVENTED_COLOR`, `INVENTED_SIZE`, `OPTION_PRICE`, `OPTION_STOCK`). Money, order and payment checks (`MoneyGuard`, `ReplyGuard`, order-summary-by-code) are separate and untouched.

What changed: product mention matching (`FactCheck.mentions`, the "loose names" step) no longer lets two words of one product's name stand for another product when a different product's kind word comes just before them (up to 3 words back, same sentence). Before, «شنطة مدرسة أطفال» (a bag) matched the school shoe «حذاء مدرسة أطفال», and `INVENTED_COLOR` then wrote the shoe's colours over the bag sentence. Tests: `FactCheckProductBoundaryTest` (6 tests, including a property test of 8,140 probes over the three demo-shop fixtures), plus all existing `FactCheck*` tests.

Limit: matching is heuristic. The property test covers swapped kind words, not every possible paraphrase.

## 2. Customer Memory
`MemoryFacts` keeps what a customer says about themselves in `chat.memory_facts` (V95), one row per fact with its source message, per conversation and tenant. It never stores shop data, phone numbers or addresses. Extraction is rule-based (no model call), runs when the message is saved, and writes at most 40 facts per message (sentences are read one by one; very long text loses nothing).
- **Sizes** are per item (`size:jacket`, `size:pants`, `size:shoes`…); an unqualified size is plain `size`. Negated sizes («مش مقاسي L», "not L", «mesh/msh … size M») are never stored and retire a matching active size; sizes in questions, other people's sizes and «this product» sizes are skipped; «قصدي XL» replaces the earlier size.
- **Budgets** are read whole («3 الاف», «2k», «1.5 الف», «5,000», «الفين», «meezaneyety 3 alaf», «bta3y 2000») including caps («مش هصرف اكتر من», «mesh hasraf aktar men»). A budget is labelled "not a product price" in the prompt and never touches `commerce.product_variants`.
- **Colours**: likes and avoids; a colour scoped to one product or kind («في الجاكيت») is not a general taste; negated or Arabizi-negated colours («mesh 3ayez abyad») are stored as *avoid*.
- **Temporary wishes** («النهارده بس», «للهدية», "just for now") live one day. Doubtful or negated intent is dropped.
- **Prompt**: only historical facts (outside the history window) relevant to the message go in, labelled with their source; `relevant()` boosts `size:<item>` when the message names the item. Memory is context only: tools remain the authority for catalogue, price, stock and orders.
- **Tenant isolation**: every lookup filters by `tenant_id` and conversation; the integration tests include two shops.
Tests: `MemoryFactsExtractionTest`, `MemoryArabiziTest`, `MemoryFactsIntegrationTest`, `MemoryExtractionDatasetTest` (123 labelled messages + 28 held out, labels never loosened; one label was changed on purpose: «i want a black jacket size M budget 1500» now expects `size:jacket`). The 1.000 scores on those sets are in-sample; the held-out set's first score before any fix was precision 0.875 / recall 0.875 and is the realistic figure.
Not supported (by design): a name in Latin letters («ana Ahmed»), constraints in Arabizi («mesh 3ayez leather»), a bare size next to an item without a size word («عايز جاكيت XL والبنطلون 42»).

## 3. Reply tracing
See `docs/reply-trace-instrumentation.md` for events, the allowlist and correlation. Summary:
- One `ReplyTrace` per inbound message: events with millisecond offsets from `System.nanoTime()` for webhook arrival, burst wait, worker queue wait, context load, memory, RAG, prompt build, every model attempt (failed and timed-out ones included, hedge requests separately), fallback, tools, rule checks and rewrites, review (verdict), outbound sends (accepted or failed, never "delivered") and the end of the reply. Evaluation runs are traced with `source='EVAL'`.
- Written to `chat.reply_traces` by a daemon thread through a bounded queue (2,000); a full queue or a database error drops the trace and counts it (`elmanassa_trace_dropped`, `elmanassa_trace_write_failures`). The reply never waits for it.
- Metrics are `elmanassa_*` on `/actuator/prometheus`, labels from small closed sets, no ids.

### Switching it off
`AI_TRACE=false` in the server `.env`, then recreate the backend (`docker-compose.test-slim.yml` passes `AI_TRACE` through). It maps to `app.ai.trace.enabled`. Off means: no trace created, nothing queued or written, no writer thread, no `elmanassa_*` metric. Replies and all other metrics (`jvm_*`, `hikaricp_*`, `http_server_requests_*`) are unchanged. Retention keeps running when it is off.

### Retention
`ReplyTraceStore.purge()` daily 04:50 Cairo: rows older than 60 days, 5,000 per batch (oldest first, by `started_at`), at most 40 batches per run, 100 ms pause between batches, on the scheduler thread; a failure is logged (once a minute) and ends the run.

### Inspecting traces (read-only SQL; every query below was run against a test database)
```sql
-- slowest customer replies of the last day and where the time went
SELECT trace_id, channel, end_to_end_ms, model_calls, summary->>'failed_model_ms' AS failed_model_ms, summary->>'fallbacks' AS fallbacks, summary->>'review_ms' AS review_ms, summary->>'burst_ms' AS burst_ms, summary->>'queue_ms' AS queue_ms FROM chat.reply_traces WHERE source = 'REPLY' AND started_at > now() - interval '1 day' ORDER BY end_to_end_ms DESC LIMIT 20;
-- every event of one trace, in order
SELECT e.ev->>'n' AS event, (e.ev->>'t')::int AS t_ms, (e.ev->>'d')::int AS d_ms, e.ev->'a' AS attrs FROM chat.reply_traces r CROSS JOIN LATERAL jsonb_array_elements(r.events) WITH ORDINALITY AS e(ev, i) WHERE r.trace_id = '<trace id>' ORDER BY (e.ev->>'t')::int, e.i;
```
Find a trace id in the logs: `Reply trace: id=…`, `Reply timeline: … trace=…`, `Reply timing: … trace=…`.

### Investigating slow GLM calls and the Qwen fallback
```sql
-- how long GLM/Qwen calls take, by outcome and failure category (a stall shows as outcome=timeout with d near the call timeout)
SELECT e.ev->'a'->>'provider' AS provider, e.ev->'a'->>'outcome' AS outcome, e.ev->'a'->>'failure' AS failure, count(*) AS calls, percentile_cont(ARRAY[.5,.9,.99]) WITHIN GROUP (ORDER BY (e.ev->>'d')::int) AS ms_p50_p90_p99 FROM chat.reply_traces r CROSS JOIN LATERAL jsonb_array_elements(r.events) AS e(ev) WHERE e.ev->>'n' = 'provider_call' AND r.source = 'REPLY' AND r.started_at > now() - interval '7 days' GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
-- how often replies fell back or hedged, and what the failed calls cost
SELECT count(*) AS replies, count(*) FILTER (WHERE (summary->>'fallbacks')::int > 0) AS with_fallback, count(*) FILTER (WHERE (summary->>'hedges')::int > 0) AS with_hedge, percentile_cont(.95) WITHIN GROUP (ORDER BY (summary->>'failed_model_ms')::int) FILTER (WHERE (summary->>'fallbacks')::int > 0) AS p95_ms_lost_to_failed_calls FROM chat.reply_traces WHERE source = 'REPLY' AND started_at > now() - interval '7 days';
-- burst and queue waits per channel
SELECT channel, percentile_cont(ARRAY[.5,.9,.95,.99]) WITHIN GROUP (ORDER BY end_to_end_ms) AS e2e_ms, percentile_cont(.9) WITHIN GROUP (ORDER BY (summary->>'burst_ms')::int) AS burst_p90, percentile_cont(.9) WITHIN GROUP (ORDER BY (summary->>'queue_ms')::int) AS queue_p90 FROM chat.reply_traces WHERE source = 'REPLY' AND started_at > now() - interval '7 days' GROUP BY 1;
-- review verdicts and cost in time
SELECT summary->>'review_verdict' AS verdict, count(*), round(avg((summary->>'review_ms')::int)) AS avg_ms FROM chat.reply_traces WHERE source = 'REPLY' AND started_at > now() - interval '7 days' GROUP BY 1 ORDER BY 2 DESC;
-- scheduled-evaluation tokens (evaluation chats are not in chat.ai_usage_log)
SELECT date_trunc('day', started_at) AS day, count(*) AS replies, sum((summary->>'tokens_in')::bigint) AS tokens_in, sum((summary->>'tokens_out')::bigint) AS tokens_out FROM chat.reply_traces WHERE source = 'EVAL' GROUP BY 1 ORDER BY 1 DESC;
```
Reading a slow reply: `provider_call` with `outcome=timeout` and `d` ≈ the call timeout, followed by `fallback_started` (`waited_ms` = what the failed model cost) and a `provider_call` with `role=fallback`, is the stall-then-Qwen path. `provider_attempt_hedged` shows the second request was sent; `provider_hedge_request` events give each request's own duration and which one won. `burst_wait`/`queue_wait` are time before processing starts.

Prometheus (names as exposed; `elmanassa_reply_latency_*`, `elmanassa_review_*`, `elmanassa_rulechecks_*`, `elmanassa_burst_pending` and `elmanassa_trace_*` were verified against the real endpoint by `ReplyTraceIntegrationTest`; a metric appears after its first event, e.g. provider and tool metrics after the first model call and tool): `elmanassa_reply_latency_seconds`, `elmanassa_reply_burst_wait_seconds`, `elmanassa_reply_queue_wait_seconds`, `elmanassa_provider_call_seconds{provider,role,purpose,outcome}`, `elmanassa_provider_attempt_seconds`, `elmanassa_provider_{attempts,failures,timeouts,hedges,hedge_losers}_total`, `elmanassa_reply_fallbacks_total`, `elmanassa_reply_fallback_duration_seconds`, `elmanassa_tool_duration_seconds{tool}`, `elmanassa_rulechecks_duration_seconds`, `elmanassa_rewrite_{duration_seconds,rounds_total}`, `elmanassa_review_{duration_seconds,verdicts_total}`, `elmanassa_outbound_{duration_seconds,failures_total}`, `elmanassa_eval_run_duration_seconds`, gauges `elmanassa_burst_pending{pool}`, `elmanassa_executor_{active,queue}{pool=meta|evolution}` (only where the async worker pools run, i.e. production defaults), `elmanassa_trace_{queue,dropped,write_failures}`. Example: `histogram_quantile(0.95, sum by (le) (rate(elmanassa_provider_call_seconds_bucket{provider="glm",outcome!="ok"}[1h])))`.

### Privacy guarantees and limits
A trace can only hold allowlisted event and attribute names (`ReplyTrace.EVENTS`, `SPECS`): small capped integers, booleans, enumerated values or narrow identifiers with at most 6 digits; exception class names, never messages. Tested with ten phone formats, tokens, an email, an address and a customer sentence. The `tenant_id` and `conversation_id` columns exist for isolation and cascade deletion (like `chat.ai_events`); no event or summary contains them. Limits: the WEB widget's HTTP response time and the unofficial Messenger/Instagram bridges are not traced; outbound is "accepted", never "delivered"; a hedge loser that finishes after the reply was saved reaches only the metrics.

## 4. Database
`V96__reply_traces.sql` creates `chat.reply_traces` only (primary key `trace_id`; FK `tenant_id → core.tenants ON DELETE CASCADE`; indexes `(tenant_id, started_at)` and `(started_at)`). Verified: V1→V96 on an empty database; V96 on a V1→V95 schema (no existing table's row count changes); re-applying is a no-op (`IF NOT EXISTS`). Rollback (manual, nothing depends on the table): `docs/reply-traces-rollback.sql` (`DROP TABLE IF EXISTS chat.reply_traces`; if Flyway recorded V96 and it must re-run, also delete its `flyway_schema_history` row).

## 5. Tests
```
cd backend
./gradlew cleanTest test                       # the full suite (use cleanTest: a plain `test` can be reported UP-TO-DATE and run nothing)
./gradlew test --tests '*FactCheck*'           # FactCheck incl. FactCheckProductBoundaryTest
./gradlew test --tests '*Memory*'              # memory unit, Arabizi, integration and the labelled + held-out datasets
./gradlew test --tests '*ReplyTrace*' --tests '*ProviderTraceTest' --tests '*ReplyMetricsTest' --tests '*InboundBurstTest'
./gradlew test --tests '*ScaleSupportIntegrationTest'
cd ../web && npm test && npm run typecheck
```
The integration tests need a local PostgreSQL database `platform_test` (default OS user). They share it and its Spring contexts; the suite runs sequentially.

## 6. Rollout and rollback (to run only after explicit approval)
1. Commit the three groups separately (see the commit plan) and let CI pass.
2. Back up the database (`pg_dump`), as for earlier deploys. Deploy with BOTH compose files (`docker-compose.yml` and `docker-compose.test-slim.yml`). Flyway applies V96 (a new table only).
3. Verify: send one real test chat; `SELECT count(*) FROM chat.reply_traces` rises by one; `elmanassa_reply_latency_seconds_count` rises on `/actuator/prometheus`; `elmanassa_trace_dropped` and `elmanassa_trace_write_failures` stay 0; grep a known test phrase in `events`/`summary` (must not appear).
4. Baseline: let it run a week, then use the queries above filtered to `source = 'REPLY'`.
5. Roll back the instrumentation only: `AI_TRACE=false` and recreate the backend (orders and data untouched), or redeploy the previous image; optionally drop the table with the rollback script. FactCheck and memory roll back with the previous image (V95's table stays, unused).

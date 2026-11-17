# AI operations guide

## Configuration (all new settings are off by default)
| Env var | Meaning |
|---|---|
| `LANGFUSE_ENABLED` | `true` to export traces. Needs host + both keys, otherwise stays off. |
| `LANGFUSE_HOST` | e.g. `https://cloud.langfuse.com` or your self-hosted URL (OTLP path `/api/public/otel/v1/traces` is appended). |
| `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` | Project keys (Basic auth). Never commit. |
| `LANGFUSE_ID_SALT` | Secret salt for the SHA-256 tenant/chat id hashes. Set it; empty salt makes ids guessable. |
| `LANGFUSE_CAPTURE_CONTENT` | `true` also sends the model's answer text (redacted: phones, e-mails, key-like tokens, 6000-char cap). Use only for synthetic shops. Prompts are never sent. |
`docker-compose.test-slim.yml` passes these through; the server `.env` needs them added at deploy time (not done here). Startup logs `Langfuse telemetry: ON/off`.

## What is traced
One trace per customer reply (`customer-reply`, with channel and the phase timeline), and one `generation` per model request or failed attempt: model code and id, latency, input/output/cached tokens, HTTP status, error class. Session id = hashed conversation, metadata `tenant` = hashed shop. Failed attempts and retries appear as ERROR generations; hedge twins appear as separate generations. Not yet traced: tool, review and rule-check spans (`AiTelemetry.step` exists, call sites not added) and Langfuse prompt versioning/datasets (use Promptfoo and the repo's `ai-eval.json` meanwhile).

## Monitoring the exporter
`AiTelemetry.sent()/failed()/dropped()` counters (not yet exposed as metrics). A rate-limited warn log `Langfuse telemetry not delivered` appears at most once a minute if the endpoint is down. A full queue (2000) drops events silently by design.

## Self-hosting Langfuse (local)
Follow https://langfuse.com/self-hosting (docker compose). Create a project, copy keys, run the backend with the env vars above. Untested here: Docker was not available and no Langfuse server was reachable, so delivery was verified only against a local stub that checks the OTLP payload.

## Promptfoo
See `ai-evaluation-strategy.md`.

## Troubleshooting
- No traces: check the startup line, then the warn log; `x-langfuse-ingestion-version: 4` is sent for real-time ingestion (otherwise up to 10 min delay).
- Traces but no tokens: the provider response had no `usage`.
- Reply slower after enabling: it should not be; the reply path only does a non-blocking `offer`. Disable with `LANGFUSE_ENABLED=false` and restart.

## Rollback
Set `LANGFUSE_ENABLED=false` (or unset) and restart: telemetry code becomes no-ops. Full revert: `git revert` the commit; no DB migration, no schema change, no new dependency was added.


## Provider configuration (round 2)
| Var | Meaning |
|---|---|
| `GLM_API_KEY` | The **Hive** key (the variable name is historical; there is no separate GLM account). Extra Hive keys: admin AI screen. Unchanged. |
| `GLM_MODEL`, `GLM_BASE_URL`, `GLM_REASONING_EFFORT` | Hive GLM model/endpoint/reasoning. Unchanged defaults. |
| `QWEN_API_KEY`, `QWEN_MODEL`, `QWEN_BASE_URL`, `QWEN_THINKING` | Alibaba fallback. Without `QWEN_API_KEY` GLM has no backup and a GLM failure shows the polite unavailable text. |
| `AI_HEDGE_MS` | Slow-call twin on another Hive key (unchanged). |
**Check that the fallback is armed:** admin AI screen shows both models available; log line `glm did not answer, using qwen: CATEGORY: ...` appears when it fires.
**Not verified live:** no Hive or Alibaba credential exists on this machine, so fallback was proven against mocked services only. Verify after deploy with a staging key by pointing `GLM_BASE_URL` at a stub that returns 503 / a capacity message and confirming the reply comes from Qwen.
**Rollback of round 2:** `git revert` the round-2 commit (restores the retry-with-sleep behaviour and removes detection). No migration, no config change needed.


## Reply feature (round 3)
- Migration `V93__message_replies.sql` runs with Flyway on start. Rollback script: `docs/rollback/V93_message_replies_rollback.sql` (run by hand, then delete version 93 from `flyway_schema_history`). Messages are never changed by either.
- No new configuration. Reply spans: `replyContext` appears in the per-reply timeline (`Reply timeline:` log line).
- Troubleshooting: a customer's native reply that the assistant treats as "not available" means we hold no copy of the quoted message (sent before this feature, from the phone itself, or an owner message to Messenger/Instagram). Rollback of the feature: revert the commit; the extra columns/table can stay.


## Round 4 operations notes
- Migration `V94__message_reply_refs.sql` (one nullable column) runs with Flyway on the next start; rollback `docs/rollback/V94_message_reply_refs_rollback.sql`. Both V93 and V94 were verified (migrate, re-run, rollback, re-migrate) on a copy of an older database with 1306 messages. This work did not touch `platform_dev` (V93 was applied there in round 3; V94 will be applied the next time that app starts).
- WhatsApp Cloud: the first text bubble of an answer to a *threaded* customer message is sent as a contextual reply (`context.message_id`). If WhatsApp refuses it the text is resent plainly (look for the debug line "Quoted WhatsApp reply refused"). Nothing else changes in sending.
- `GET /api/v1/shop/chat/history?...&around=ID` is public and unthrottled like the history endpoint it extends.
- Troubleshooting: a customer reply the assistant calls "not available" means we hold neither the message nor a platform quote (see ai-reply-context.md). The digest and the reply block add to the end of the system prompt only (spans `replyContext` and `earlierDigest` in the reply timeline).


## Final phase operations notes
- Migration `V95__memory_facts.sql` (a new table, nothing else) runs with Flyway on the next start; rollback `docs/rollback/V95_memory_facts_rollback.sql` (loses only the structured facts). Verified on a copy of an older schema (1306 messages): migrate V93+V94+V95, constraints (one ACTIVE fact per key, type check), re-run, rollback of all three, re-migrate. This phase did not touch `platform_dev` (schema 93; `replydemo` and `replyinbox` still there).
- `app.ai.rag.hybrid` (env: none yet; set it as a Spring property) is **false**: rank fusion of the search by meaning and the word search in `shop_knowledge`. Turn it on only after comparing it with the production embedding; the benchmark that motivated it used a stand-in.
- Memory facts live and die with their conversation (cascade); they are scoped by shop and conversation in every query. To forget a customer's facts, delete the rows of that conversation.
- The prompt now carries the UNTRUSTED TEXT rule (both modes) and the order on file shows `payment status`; a reply that claims a payment without `payment status PAID` is rewritten or cut (log line "Claims check ... a payment received that no order shows as paid").

## Second-look review mode (since the A/B benchmark of 2026-11-17)

- `AI_REVIEW_MODE=conditional` (the default): a plain draft whose every figure is in the facts the model was given skips the second model call; everything else is reviewed. `ReviewGate` routes to the review any draft with a promise, offer, urgency, picture, guarantee or formal address, a denial or out-of-stock claim, store policy (minimum order), delivery wording, order-state wording, a new figure, or more than 700 characters.
- `AI_REVIEW_MODE=always`: every non-trivial draft is reviewed. This is the immediate rollback: set it and recreate the backend container. An unknown value also means `always`.
- The mode actually in effect is reported by `GET /api/v1/platform/ai-health/review-policy` (platform admin) and logged at startup (`AI second-look review: ... effective mode=...`).
- The deterministic checks (prices, stock, variants, minimum order, FactCheck, MoneyGuard, the order guards) run on the sent text in both modes; the mode only decides about the model call.
- Benchmark data, the pre-registered protocol and the blind-review pack are kept outside the repo with the benchmark artifacts (`PROTOCOL.md`, `blind_pack.json`, `blind_key.json`).

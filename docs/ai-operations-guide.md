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

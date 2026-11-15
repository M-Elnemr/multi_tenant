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

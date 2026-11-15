# Tool decisions (2026-11-15)

| Tool | Decision | Reason |
|---|---|---|
| Langfuse | **Adopted** (OTLP/HTTP JSON, no SDK, no new dependency) | Fills the missing trace/usage view. The legacy ingestion API is deprecated (sunset 2026-11-16 on Cloud), so OTLP is used. Verified only against a local stub, not a real server. |
| Promptfoo | **Adopted** (config generator, custom provider, offline self-test) | Runs the real backend path with the existing GLM. Not yet run end to end. |
| Ragas | Not adopted | Needs an LLM judge in a Python service plus a GLM key to be meaningful. The existing `rag_eval.py` measures retrieval directly with labelled cases and negatives, which is a stricter check of the thing Ragas would only approximate. Revisit for faithfulness scoring when a judge key is available. |
| DSPy | Not adopted | Candidate tasks (order extraction, quantity, variant, intent) are already handled by deterministic code plus guards; optimisation needs labelled data and live GLM calls, neither available here, so no benefit could be measured. |
| OpenTelemetry (Java SDK) | Not adopted separately | `ReplyTimeline` + Micrometer already give phase timings; Langfuse spans are emitted as OTLP by `AiTelemetry`. A full OTel agent adds a dependency and overhead without a demonstrated gap. |
| GlitchTip | Not adopted | Needs its own Postgres/Redis/web infra; application logs already carry failures. Recommend only if the team wants alerting on exceptions. |
| LiteLLM | Not adopted | Hedging, key pool, throttle, retries and fallback are implemented in `OpenAiProvider`/`ChannelAi`; a Python proxy would change the request path and risk the model-lock requirement for no gain. |

Future recommendations (not implemented): a faster/hosted GLM endpoint or lower reasoning only after an A/B; trimming a model round (selective review) only with the order checks as the gate.


## Round 2 review
**The deprecation:** the sunset date (2026-11-16 on Langfuse Cloud) comes from Langfuse's own documentation for its *legacy batch ingestion API* (`/api/public/ingestion`). It is not a runtime warning, not an SDK, not Hive and not Alibaba. This repo never calls that endpoint (grep: only a comment mentions it); `AiTelemetry` posts to the supported OTLP endpoint `/api/public/otel/v1/traces`. No migration was needed. Real delivery to a Langfuse server remains **unverified**: this machine has no Docker/Podman/Colima, and no Langfuse project/credentials; only a local stub receiver checks the payload (spans, categories, hashed ids, no content by default).
| Tool | Decision | Reason |
|---|---|---|
| Typesense / Meilisearch | Not adopted | Retrieval is in-memory vector search per tenant with Postgres storage; no measured relevance gap that a search engine fixes (`rag_eval.py` baseline top-3 0.77). A second datastore adds tenant-isolation surface. |
| Temporal | Not adopted | Reply flow is one request with a 45 s budget; no long-running workflow needs durable execution. |
| Activepieces | Not adopted | Integrations are code with tests; a no-code runner adds an unreviewed path into orders. |
| PostHog / Metabase | Not adopted | Visitor analytics and platform reports already exist in the app; no AI-quality question they answer that Langfuse + `ai_usage_log` do not. |
Earlier decisions (Promptfoo, Ragas, DSPy, OpenTelemetry, GlitchTip, LiteLLM) stand; LiteLLM is even less justified now that fallback is implemented in `OpenAiProvider`/`ChannelAi` with tests.

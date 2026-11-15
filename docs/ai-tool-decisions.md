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

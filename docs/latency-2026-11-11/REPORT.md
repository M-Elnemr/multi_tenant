# AI Seller latency audit (2026-11-11): where the time goes and what to do

Everything here is measured, not assumed. "Baseline" = the code deployed as `c8466e2` (DeepSeek V4.1 Flash, reasoning low, lean prompt, review on DeepSeek, history 24, hedge 25 s, review cap 8 s).

## 1. The reply lifecycle and every model request

One customer message (WEB; WhatsApp/Meta add a 5 s quiet window before the AI starts, a "still on it" message after 5 s, and ~1.5 s typing delay per message part):
history load, system prompt build (DB), fast paths (instant greeting/thanks, plain "yes" confirmation, code-made order summary), then `converse`:

| # | Request | When | Critical path |
|---|---|---|---|
| 1..n | model loop rounds (tool call rounds, then the answer) | always, unless a fast path answered | yes |
| +1 | review (DeepSeek on its own draft, 8 s cap, one try) | draft is not "trivial" (any number, availability talk, > 160 chars) and < 20 s elapsed | yes |
| +k | rewrite loops (rule/money/claims checks) | a check fails and < 20 s elapsed, fewer than 5 rounds | yes |
| +all | second opinion on GLM | unresolved problem and < 27 s elapsed, no order/summary made | yes |
| 1 | embedding of the query inside `search_products` / `shop_knowledge` (Alibaba, cached per query) | new query | yes |
| 0..1 | hedge twin | a call still pending after the hedge threshold | no (replaces the stuck call) |

## 2. Measured breakdown

**Code and database: 0.10-0.27 s per reply** (local Postgres, a shop with 12 / 40 / 80 products of 9 variants each, instant stand-in model, timeline of `ReplyTimeline`): prep 3-11 ms, system prompt 24-111 ms, search tool 22-79 ms, checks 12-69 ms, saving 1-5 ms. Even at five times slower on the VPS this is about 1-5% of a 15-30 s reply. Caching or parallelising the prelude would add stale-price risk for nothing. Not done.

**Everything else is the model.** Direct tests of DeepSeek on Hive from the VPS (`hive_prompt_size.py`, `hive_shape_hangs.py`):
- Prompt size barely matters: 119 tokens vs 12,300 tokens moved the median call from about 3.9 s to 4.4-6.5 s. Output tokens matter: a call costs about 1.5 s plus about 29 ms per output token, and reasoning tokens are most of the output (a plain question produced 278-299 reasoning tokens at `low`; with reasoning off, 71 tokens and a 2.6 s median).
- **The tail is provider stalls, not long generations.** In 36 production-shaped calls (11k tokens, 15 tools, low, max 1000 tokens): median 3.9 s, P90 12.3 s, and 3 calls (8%) stalled 63 s, 72 s and 300 s while producing only 80-121 tokens.

**Production baseline** (the same scenarios the earlier benchmarks used, 40 conversations x 2 trials on the three shops, TEST channel, model `default` = the production route; `baseline-before-t*.json.gz`, summarised by `lat_report.py`):

| | |
|---|---|
| model replies | median 18.2 s, P90 29.6 s, P95 32.3 s, mean 17.6 s |
| model calls per model reply | 2.36 (tool + answer + review is the typical 3) |
| tokens per model reply | 23.6k input (32% cached), 407 output |
| replies that hit a stalled DeepSeek call and fell back to GLM | 38 of 118 (32%), logged as `HttpTimeoutException: request timed out` |
| orders fully correct | 12/12 (item and quantity 12/12, first `prepare_order` 6/6) |
| judge (graded) | 64.0% over 68 conversations (0 ungraded) |

By scenario type (median s): greeting/identity 10.1, ambiguous 5.6, product inquiry 18.9, comparison 25.0, several variants 22.9, out of stock 23.1, multi-product 29.2, RAG/policy 18.0, negotiation 17.8, order preparation 17.2.

## 3. Findings, ranked by evidence

| # | Opportunity | Measured evidence | Expected impact | Risk | Status |
|---|---|---|---|---|---|
| 1 | **Stalled DeepSeek calls**: hedge at 25 s with a 37 s call timeout waits through stalls | 8% of calls stall > 60 s; 32% of baseline replies fell back; 25 s hedge fired 3 times | removes the 30-45 s outliers; P90/P95 most | a twin is billed for the ~15% of calls slower than the threshold; `AI_HEDGE_MS=8000` (call timeout then 20 s) | **needs your approval** (config) |
| 2 | Review on reasoning off | same verdict as `low` on all 10 test drafts x 2 runs (7 flawed flagged, 3 valid passed), 3.0 s vs 4.4 s median, 98 vs 314 output tokens | about 1.4 s on ~65% of replies | a different verdict on drafts not in the test set; setting `AI_REVIEW_REASONING=` (empty) restores `low` | **implemented** (default `none`) |
| 3 | `show_products` round | 79 `show_products` calls in 256 replies (31%), each its own model call | about 5 s on those replies | cards chosen by code: 82% of shown products are named in the reply, 12% partly, 6% not at all; WhatsApp pictures affected | proposal only |
| 4 | Selective review | review is 1 call in most replies; corrections were frequent in live checks | up to a call per reply | review catches real slips; needs an on/off A/B with the judge | proposal only |
| 5 | Reasoning policy per call type | at `low` a trivial question spends ~280 reasoning tokens | up to ~1.5 s per call | order turns benefited from `low` (first `prepare_order` 100% vs 50%) | proposal only |
| 6 | Prelude caching/parallelism | 0.10-0.27 s total | none | stale prices | rejected |
| 7 | Prompt trimming | 12k vs 0.1k tokens: +0.5-2.5 s | small | loses rules | rejected |

## 4. Implemented (this change)

- `ReplyTimeline` (new): per reply, the phases (prep, prompt, products, flow, post) with every model call, tool, review and check and its duration, in one log line (`Reply timeline: ...`) and in each benchmark turn. No-op outside a reply, per-thread, can never fail a reply.
- Review reasoning level (`app.ai.review.reasoning-effort` / `AI_REVIEW_REASONING`, default `none`): applies only to the review call, only to a model that takes a level, never leaks to other calls.
- The fallback log line now includes the cause of the failure.
- Files: `ReplyTimeline.java` (new), `AiBudget.java`, `OpenAiProvider.java`, `ChatService.java`, `AiEvalService.java`, `ChannelAi.java`, `application.properties`, `docker-compose.test-slim.yml`.
- Tests: `ReplyTimelineTest` (phases, no-op, isolation under 8 concurrent replies), `OpenAiProviderReasoningOverrideTest`, `DeepSeekSelfReviewIntegrationTest` (conversation `low`, review `none`), `ReplyOverheadBenchmarkTest` (prints the overhead with `BENCH_OVERHEAD=1`).

## 5. After

Not measured yet: the production benchmark for the new code needs a deploy, and the hedge change a configuration change, both of which wait for approval. The same plan files (`plan-before-t*.json`) and `lat_report.py` give the comparison.

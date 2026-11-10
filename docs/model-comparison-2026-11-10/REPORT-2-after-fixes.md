# AI model benchmark, re-run after the reliability fixes (2026-11-10/11)

Two identical trials, 1,104 conversations, three live shops, production reasoning settings. Raw data: `rerun-after-fixes-trial1.json.gz`, `rerun-after-fixes-trial2.json.gz`; plan: `plan-rerun-after-fixes.json`; scripts: `bench2.py`, `report2.py`. The earlier benchmark is `REPORT.md` (data `main-run.json.gz`, `orders-rerun.json.gz`).

## 1. Executive summary

- **The fixes work.** Order quantity: 34 of 36 order conversations are fully correct for every model and prompt (12/12 on the six order scenarios of the previous benchmark, against 4 to 6 of 6 before), and the quantity and variant were right in 36 of 36 for all six configurations. The only two failures per configuration are one scenario that exposed a different, pre-existing backend bug (a customer name containing «حي», for example «فتحي», is written as the address). First attempts at `prepare_order` stopped failing on variant ids: on the same six scenarios GLM went from 5% to 100% first-try success, DeepSeek from 14% to 83%, Qwen from 0% to 58%. Search by meaning was unavailable in 6 of 1,104 conversations (0.5%), against 35 of 480 (7.3%) before, and the model was told in every one of them.
- **Quality is a three-way tie.** Pooled judge scores: DeepSeek 60.9%, GLM 59.5%, Qwen 58.3%; every pairwise difference is inside the noise (one run moves a configuration by 2.4 points on average, 5.6 at most). The lean prompt scored higher than the full prompt for all three models, significantly only for DeepSeek (+7.3 points, interval +1.2 to +13.2, one of twelve comparisons, so treat it as a lead, not a result).
- **The real differences are speed, cost and tool discipline.** Qwen is the fastest (6.5 s average, 15 s P95) and cheapest ($0.00087 per successful conversation); DeepSeek makes the fewest tool errors (2.3%) and is the most expensive ($0.00353); GLM is in between on cost and slowest at P95 (25 s) with the flakiest endpoint (143 hedged requests, 13 unanswered calls). Qwen makes the most tool errors (18.8%): it calls `prepare_order` before the customer has given any details, with placeholders, and once copied the example mobile number from an error message. The server's locks refused every such attempt, so no order was ever wrong.
- **Recommendation:** keep Qwen as primary (its extra tool errors cost tokens, not correctness); keep GLM as the second opinion for now; DeepSeek is the better-behaved candidate for second opinion or an order-heavy path, but not as default (about 3.2x Qwen's total cost, 4.1x per successful conversation). Details in section 14.

## 2. Verified reasoning configuration

You asked for the production settings: GLM `low`, DeepSeek `none`, Qwen thinking off. Hive's docs confirm what each value means: GLM accepts `low`, `high`, `max` (no `none`; default `max`); DeepSeek accepts `none`, `low`, `high`, `max` (default `high`, `none` disables thinking). For Qwen3.7-Flash, Alibaba documents thinking as on by default with `enable_thinking` and `thinking_budget` as the switches (`reasoning_effort` is not documented for it). Verified three ways: in the code, on the wire (the provider layer records the outgoing request of every conversation) and in the responses.

| Model | Setting sent (every call, 1,104 conversations) | Reasoning seen in responses |
|---|---|---|
| GLM-5.3-Flash (`zai-org/glm-5.3-flash`, Hive) | `reasoning_effort: "low"`, `max_tokens: 2000`, `temperature: 0.3`, 15 tools | genuine: 31,502 reasoning tokens in 616 of 1,179 calls |
| DeepSeek V4.1 Flash (`deepseek-ai/deepseek-v4.1-flash-1017`, Hive) | `reasoning_effort: "none"`, same cap, temperature, tools | off: 135 tokens in 5 of 1,288 calls (0.4%, stray) |
| Qwen3.7-Flash (`qwen3.7-flash`, Alibaba) | `enable_thinking: false`, same cap, temperature, tools | off: 296 tokens in 11 of 1,137 calls (1%, stray) |

The output cap is `max_tokens: 2000` (previous benchmark: 1000); no response ended on the cap (`finish_reason`: only `stop` and `tool_calls`). Temperature 0.3 for all three is a benchmark setting; production sends none and uses each provider's default (Hive documents 1 for GLM and DeepSeek). Judge: the same Claude judge and rubric, blind to model and settings.

## 3. Confirmation of the fixes (code, tests, live)

All three were deployed before the run (commit `e649087` and the tooling commit `6599c55`); the pre-flight run confirmed the live backend.

| Fix | Where | Regression tests |
|---|---|---|
| A. Quantity | `OrderQuantity` (one conservative reader) used by `orderByCode`, `severalByCode`, `minimumReachedByCode` and a lock in `prepareOrder` that refuses lowering a stated quantity; quantities read only from messages after the last placed order | `OrderQuantityIntegrationTest` (8, through the real chat path: quantities 1 to 4 in several phrasings, said several messages before checkout, changed before the summary, address and phone digits never read as a quantity, model cannot lower or raise), `OrderQuantityTest` (3) |
| B. Variant ids | `prepare_order` items by `slug` + `options` (the shop resolves the variant; the customer-said-it locks still apply) | `OrderVariantReferenceIntegrationTest` (6) |
| C. RAG | embedding query: 1.5 s connect timeout, one retry inside the bounded wait, rest only after 3 failed queries in a row (20 s growing to 5 min), `UNAVAILABLE` told to the model and recorded (`RAG_UNAVAILABLE`), health page `/ai-health/rag`, ops alert `RAG_DEGRADED` | `RagUnavailableIntegrationTest` (3), `OpenAiEmbedderTest` (7) |

Full suite before deploy: 591 tests, 590 passing; the one failure (`FollowUpIntegrationTest`) is the known intermittent test that fails in some full runs and passes alone (it asserts a global scheduler count in a shared database); it failed the same way before these changes.

## 4. Setup and exact counts

| Shop | Scenarios | = comparable with the previous benchmark | + quantity regression |
|---|---|---|---|
| tech-zone (تك زون) | 29 | 25 | 4 |
| nile-style (نايل ستايل) | 31 | 27 | 4 |
| beit-elshawarma (مطعم بيت الشاورما) | 32 | 28 | 4 |
| **per model and prompt** | **92** | **80** (74 judged + 6 order) | **12** |

92 x 3 models x 2 prompts = 552 conversations per trial, x 2 trials = **1,104**, all completed (18 of 18 cells, 0 mismatches). Same scenario file, prompts (full and lean), tools, RAG, code-side checks as before; temperature 0.3; the three models ran in parallel, one thread each.

## 5. Main results (both trials pooled; 74 judged conversations per model and prompt per trial)

| Model | Prompt | Judge pass rate | Fully OK judged (of 148) | Orders correct (of 36) | Latency avg / P50 / P95 (s) | Tool errors |
|---|---|---|---|---|---|---|
| GLM | full | 56.2% (199/354) | 45 | 34 | 9.7 / 8.1 / 25.3 | 14 of 209 (6.7%, both prompts) |
| GLM | lean | 62.7% (222/354) | 58 | 34 | 9.0 / 7.1 / 23.4 | |
| DeepSeek | full | 57.9% (205/354) | 46 | 34 | 8.5 / 7.8 / 18.0 | 8 of 351 (2.3%) |
| DeepSeek | lean | 63.8% (226/354) | 61 | 34 | 8.7 / 8.0 / 19.6 | |
| Qwen | full | 57.3% (204/356) | 51 | 34 | 6.5 / 5.8 / 15.2 | 39 of 208 (18.8%) |
| Qwen | lean | 59.3% (210/354) | 56 | 34 | 6.7 / 6.0 / 16.2 | |

Are the differences real? Paired bootstrap over the same 74 conversations (95% intervals): every model-versus-model difference includes zero (both prompts: GLM-DeepSeek -1.3 [-5.4, +2.9]; GLM-Qwen +1.2 [-4.1, +6.7]; DeepSeek-Qwen +2.5 [-2.6, +8.0]). Trial 1 versus trial 2 differ by 2.4 points on average per configuration (largest 5.6), and the same conversation scored exactly the same in only 63% of cases: **differences under about 5 points are noise.** Tool-error differences are real: GLM vs DeepSeek p = 0.013, Qwen vs either p < 0.001 (Fisher exact).

## 6. Full prompt vs lean prompt

Lean minus full, judge points (95% interval): GLM +6.6 (-0.6, +14.1) within noise; **DeepSeek +7.3 (+1.2, +13.2)**; Qwen +1.4 (-4.8, +7.5) within noise. Lean removes 17% (GLM), 16% (DeepSeek) and 20% (Qwen) of input tokens per reply (GLM 22,385 to 18,586; DeepSeek 26,269 to 22,049; Qwen 22,354 to 17,840). Real cost per successful conversation: GLM -23%; DeepSeek -10%; Qwen +34% (cache hit rate fell on Qwen: $0.0123 cached cost to $0.0046, and its uncached input doubled). Lean did not hurt quality in this data; whether to switch is a cost decision per model and a question for a further repeat, not decided here.

## 7. Per shop (judge pass rate, full / lean)

| Shop | GLM | DeepSeek | Qwen |
|---|---|---|---|
| tech-zone | 52.0 / 61.8 | 56.9 / 67.6 | 56.9 / 61.8 |
| nile-style | 64.5 / 58.1 | 63.7 / 65.3 | 55.6 / 54.8 |
| beit-elshawarma | 51.6 / 68.0 | 53.1 / 59.4 | 59.2 / 61.7 |

About 50 to 60 judged checks per cell: individual cells move by 5 to 10 points between runs; no shop-level ranking is reliable.

## 8. Order correctness (code-checked, not judged)

| Model | Prompt | Main 6 scenarios (x2 trials) | Regression 12 (x2 trials) | Quantity right (all 36) |
|---|---|---|---|---|
| all three | full and lean | 12/12 | 22/24 | 36/36 |

Previous benchmark, same six scenarios: GLM 6/6 and 5/6, DeepSeek 5/6 and 4/6, Qwen 4/6 and 6/6 (every failure was the "two" quantity defect). The 2 failures per configuration are scenario 931 in both trials, a **backend/business-logic bug**, not a model failure: for a customer who writes «اسمي مازن فتحي، 0105…، القاهرة المعادي…» the code that builds the address takes the first comma-separated part containing the address pattern «حي», which «فتحي» contains, so the summary shows «العنوان: اسمي مازن فتحي». Quantity and variant were right in that order. Not fixed in this task (you asked for no production changes).

First `prepare_order` attempt, on the same six scenarios as before (calls it / first try OK / failed then recovered / never succeeded): GLM 16: 16 (100%) / 0 / 0 (before: 19: 1 (5%) / 18 / 0); DeepSeek 6: 5 (83%) / 0 / 1 (before: 14: 2 (14%) / 12 / 0); Qwen 19: 11 (58%) / 7 / 1 (before: 21: 0 (0%) / 20 / 1). All 17 "never succeeded" conversations across the whole benchmark ended with a correct order: the refused calls were **premature calls before the customer had given name, mobile or address** (the server correctly asked for them), which the code-side order logic then completed. They cost tokens, not correctness.

## 9. Tool and RAG reliability

| Model | Tool calls | Errors | By kind | Provider: unanswered calls / hedged twins | Second opinions asked (adopted) | Model failures / fallbacks |
|---|---|---|---|---|---|---|
| GLM | 209 | 14 (6.7%) | sequence 9, missing details 4, other 1 | 13 / 143 (12% of calls) | 7 to Qwen (7) | 0 / 0 |
| DeepSeek | 351 | 8 (2.3%) | missing details 5, sequence 2, other 1 | 0 / 19 | 2 to GLM (2) | 0 / 0 |
| Qwen | 208 | 39 (18.8%) | missing details 26, sequence 7, **item reference 5**, other 1 | 1 / 20 | 12 to GLM (12) | 0 / 0 |

(Fallbacks are zero by design: the compared models were picked by name.) The old `Invalid variantId` failures are gone for GLM and DeepSeek; Qwen still has 5 item-reference errors (it passes a slug it mangled) among its 39. **RAG:** the knowledge tool was offered in every reply of every conversation. The embedding provider had 6 failed queries of 90 in trial 1 (5 connect timeouts, 1 timeout; none rescued by the retry, so they were outages, not slowness) and 0 of 18 in trial 2 (trial 2 mostly hit the query cache). Six conversations (3 DeepSeek, 3 Qwen) had a reply while search by meaning was unavailable; each was told "unavailable", none told the customer the shop lacks something, and they are included in all totals (judge pass rate 69.2% on 13 checks, too few to compare).

## 10. Latency and tokens (model-answered replies, 512 per model)

| Model | Prompt | Avg / P50 / P95 (s) | Input per reply | Output per reply | Tokens per conversation | Reasoning tokens |
|---|---|---|---|---|---|---|
| GLM | full | 9.7 / 8.1 / 25.3 | 22,385 | 277 | 31,530 | 17,319 |
| GLM | lean | 9.0 / 7.1 / 23.4 | 18,586 | 255 | 26,213 | 14,183 |
| DeepSeek | full | 8.5 / 7.8 / 18.0 | 26,269 | 225 | 36,861 | 0 |
| DeepSeek | lean | 8.7 / 8.0 / 19.6 | 22,049 | 245 | 31,018 | 135 |
| Qwen | full | 6.5 / 5.8 / 15.2 | 22,354 | 208 | 31,391 | 158 |
| Qwen | lean | 6.7 / 6.0 / 16.2 | 17,840 | 217 | 25,124 | 138 |

Previous benchmark averages: GLM 11.5 / 9.5 s, DeepSeek 14.9 / 13.4 s, Qwen 8.2 / 7.2 s: the provider speeds vary by hour and load (DeepSeek was 43% faster this time), so latency is a ranking aid, not a constant. Reasoning tokens are billed inside the output tokens.

## 11. Cost (provider list prices: GLM 0.05/0.17/0.01, DeepSeek 0.10/0.35/0.003, Qwen 0.03/0.13/0.003 per 1M input/output/cached)

| Model | Prompt | Input (uncached) | Cached input | Output (incl. reasoning) | Second opinions | Total | Per conversation | **Per successful conversation** |
|---|---|---|---|---|---|---|---|---|
| GLM | full | $0.1348 | $0.0304 | $0.0121 | $0.0014 | $0.1785 | $0.00097 | **$0.00226** |
| GLM | lean | $0.1232 | $0.0229 | $0.0111 | $0.0019 | $0.1591 | $0.00086 | **$0.00173** |
| DeepSeek | full | $0.2495 | $0.0127 | $0.0202 | $0.0000 | $0.2823 | $0.00153 | **$0.00353** |
| DeepSeek | lean | $0.2694 | $0.0089 | $0.0220 | $0.0013 | $0.3015 | $0.00164 | **$0.00317** |
| Qwen | full | $0.0491 | $0.0123 | $0.0069 | $0.0058 | $0.0741 | $0.00040 | **$0.00087** |
| Qwen | lean | $0.0907 | $0.0046 | $0.0072 | $0.0030 | $0.1055 | $0.00057 | **$0.00117** |

Totals cover 368 conversations per model per prompt pair; "successful" = judged conversation with every rubric line passed and no failure, or order placed fully right (GLM 171, DeepSeek 175, Qwen 175 successes of 368). GLM reasoning tokens cost $0.0054 of its output cost. **Caveat on GLM:** 143 of its 1,179 calls were hedged (the same request sent twice after 8 s, first good answer wins); only the winning answer's tokens are counted, so if the provider bills the cancelled twins its real cost is up to about 12% higher. Judge (Claude) cost, kept apart: 623,556 input and 634,995 output tokens = $0.38 for all 1,104 conversations. Embedding calls (about 108 in the whole benchmark) are not priced here.

## 12. Failure analysis (judged conversations that were not fully successful; 296 per model)

| Cause | GLM | DeepSeek | Qwen |
|---|---|---|---|
| Model quality | 156 | 144 | 147 |
| Judge limitation (8 rubric lines no model can pass: the judge cannot see tool calls such as "calls delivery_quote"; the greeting lines) | 13 | 19 | 17 |
| Backend/business logic (the instant code answer to «hi» / «السلام عليكم»: replies in Arabic and asks the name first) | 24 | 24 | 24 |
| Tool integration (a tool call refused in a conversation that also failed) | 0 | 1 | 0 |
| RAG failure | 0 | 1 | 1 |

Real examples: (1) **Backend bug**, «اسمي مازن فتحي» becomes the address line (scenario 931, all models). (2) **Qwen tool discipline**, before the customer gave any details it called `prepare_order` with `customerName="مجهول", phone="مجهول"`, then with `01012345678` (the example in the refusal text), then asked for a WhatsApp consent the customer never gave; each call was refused by the server. (3) **Model quality, consistent**: the "full outfit for a 2,500 budget" scenario passes 100% for GLM and DeepSeek and 17% for Qwen (one scenario, 12 checks). (4) **Wording**: the judge named dialect, gender or an odd word in only 4 GLM checks (0.6%), 1 DeepSeek (0.1%) and 7 Qwen (1.0%), for example Qwen «في عتبته» for «في علبته», DeepSeek «قوليلي» (feminine) to a customer addressed with «يا فندم».

## 13. Against the previous benchmark

| | Previous | Now |
|---|---|---|
| Judge pass rate, same 74 conversations (full / lean) | GLM 54.2 / 61.0, DeepSeek 57.1 / 61.6, Qwen 57.6 / 53.1 | GLM 56.2 / 62.7, DeepSeek 57.9 / 63.8, Qwen 57.3 / 59.3 |
| Order scenarios correct (6 per configuration) | 4 to 6 of 6; every failure was the "two" defect | 6 of 6 for all (12 of 12 over two trials) |
| Quantity "two" in code-built summaries | wrong in 6 of 12 plays | right in 36 of 36 |
| First `prepare_order` try OK (same six scenarios) | GLM 5%, DeepSeek 14%, Qwen 0% | GLM 100%, DeepSeek 83%, Qwen 58% |
| Tool error rate | GLM 17.0%, DeepSeek 8.5%, Qwen 15.7% | GLM 6.7%, DeepSeek 2.3%, Qwen 18.8% |
| Conversations with a reply made without RAG | 35 of 480 (7.3%), silently | 6 of 1,104 (0.5%), told to the model and recorded |
| Output cap / temperature / reasoning | 1000 / 0.3 / production | 2000 / 0.3 / production (unchanged) |

All judge changes are within the 2.4-point trial noise except Qwen lean (+6.2), which fell inside the same noise band of its own two trials (62.1% and 56.5%).

## 14. Answers and recommendation

- **Does DeepSeek perform better with reasoning at Low? / Was its earlier result affected by `none`?** Not tested: you asked to keep `none`. Evidence available: with `none` DeepSeek ties GLM and Qwen on quality, has the fewest tool errors, and its lean-prompt gain is the only significant one; nothing here suggests `none` handicaps it, but `low` itself was not measured. A one-trial DeepSeek `low` run (about 25 minutes) would answer it.
- **Does Qwen remain competitive with thinking enabled?** Not tested (thinking stays off). With it off Qwen is statistically level on quality, fastest and cheapest, but weakest on tool discipline.
- **Does GLM still give better order accuracy or recovery?** No: all three are identical (34 of 36 per configuration, the same two backend-bug failures). On first-try tool success GLM is best (100% on the six scenarios), but it has the flakiest endpoint.
- **Most reliable Egyptian Arabic sales conversations:** judge quality is a tie; DeepSeek made the fewest wording slips (1 vs 4 vs 7 of about 700 checks), a small, not statistically established edge; Qwen failed the full-outfit scenario consistently.
- **Most reliable tools and multi-turn orders:** DeepSeek (2.3% tool errors, no unanswered calls, 19 hedges), then GLM (6.7%), then Qwen (18.8%); order correctness itself is protected by server logic and identical for all.
- **Primary model:** Qwen, unchanged: equal quality, 6.5 s average and 15 s P95, $0.00087 per successful conversation (GLM 2.6x, DeepSeek 4.1x).
- **Backup / second opinion:** keep GLM for now (7 + 12 adopted second opinions, a different company from Qwen, cheaper than DeepSeek); DeepSeek is the better-behaved candidate (it is the most reliable endpoint and most disciplined with tools, and a second opinion fires on only about 2% of replies, so its higher price barely matters), but switching needs its own check of second-opinion quality first.

## Limits

Two trials, 74 judged conversations per cell: quality differences under about 5 points cannot be seen. The judge is a language model with a strict rubric (8 of 106 lines are unpassable by design). Temperature 0.3 is steadier than production's defaults. The three models ran in parallel on one server, so latencies include shared load. Costs use list prices (Qwen's workspace is a US-region endpoint that may bill differently). Trial 2 hit the embedding query cache, so RAG exposure was lower there. The scripted order scenarios are fixed customer lines.

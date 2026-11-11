# AI model benchmark, all three models with reasoning on (2026-11-11)

This is the run you asked for: **GLM `low`, DeepSeek `low`, Qwen thinking on**, after the reliability fixes. Two identical trials, 1,104 conversations, three live shops. Raw data: `rerun-reasoning-on-trial1.json.gz`, `rerun-reasoning-on-trial2.json.gz`; plan: `plan-rerun-reasoning-on.json`; scripts: `bench2.py`, `report2.py`, `compare_low.py`.

**Why a second re-run exists.** The first re-run after the fixes (`REPORT-2-after-fixes.md`) used GLM `low`, DeepSeek `none` and Qwen thinking off, because I read one of your messages as "keep the production settings". That contradicted your first instruction (all three with reasoning on, DeepSeek not `none`) and I should have asked. This report is the run that follows your first instruction, and it also lets the two settings be compared directly.

## 1. Executive summary

- **Turning reasoning on did not measurably change quality for any model.** Judge pass rate, reasoning on minus production reasoning, same 74 conversations: GLM -0.1 points (the control: its setting did not change, so this is pure noise), DeepSeek +1.3 [-3.0, +5.6], Qwen -1.2 [-5.4, +2.8]. All inside the noise. So the earlier DeepSeek `none` result was not shown to be handicapped.
- **It made two models much slower and dearer.** DeepSeek: 8.6 s to 13.3 s average latency (1.55x), P95 18.8 s to 28.4 s, cost per successful conversation +23%. Qwen: 6.6 s to 13.8 s (2.09x), P95 15.7 s to 27.5 s, +32%. GLM, whose setting did not change, stayed the same.
- **It helped one thing: DeepSeek's tool discipline.** Its first `prepare_order` attempt succeeded in 24 of 24 conversations (100%) with reasoning on, against 5 of 10 with `none` in the previous run, and its tool error rate is 1.7%. Qwen's did not improve (15.6% tool errors, first try OK in 11 of 29).
- **DeepSeek now leads Qwen on quality, narrowly:** 62.4% vs 57.3% pooled; difference +5.0 points, interval [+0.2, +10.2], the lower bound only just above zero while one trial moves a configuration by 4.3 points on average. Treat it as a lead worth confirming, not a settled result. GLM (59.9%) is level with both.
- **Orders are identical and correct for all three** (34 of 36 per configuration; quantity and variant 36 of 36; the 2 failures are one scenario that exposes a separate backend bug, below).
- **Recommendation:** keep Qwen as primary with reasoning **off** (reasoning on doubled its latency and raised its cost for no measurable gain); keep GLM as the second opinion; DeepSeek with reasoning on is the best-behaved candidate for a backup or for order-heavy flows, but about 3.7x Qwen's cost per successful conversation. Section 14.

## 2. Verified reasoning configuration

Hive's own docs: GLM accepts `reasoning_effort` `low`, `high`, `max` (default `max`); DeepSeek accepts `none`, `low`, `high`, `max` (default `high`). For Qwen3.7-Flash, Alibaba documents thinking as on by default, controlled by `enable_thinking`, with `thinking_budget` as a cap on thinking tokens (its `reasoning_effort` parameter is documented for other model families, not for this one). Names that look alike are **not** equivalent, so each was measured:

| Model | Setting sent on every call (1,104 conversations) | Reasoning in responses | Verdict |
|---|---|---|---|
| GLM-5.3-Flash | `reasoning_effort: "low"` | 652 of 1,174 calls, avg 55 tokens per reasoning call (70 per reply) | genuine low |
| DeepSeek V4.1 Flash | `reasoning_effort: "low"` | 1,319 of 1,320 calls (100%), avg 176 tokens per call (453 per reply) | genuine low |
| Qwen3.7-Flash | `enable_thinking: true`, `thinking_budget: 256` | 1,165 of 1,177 calls (99%), avg 232 tokens per call (527 per reply) | thinking on, capped |

**Limits of the comparison, stated plainly.** Qwen has no "low" level for this model. Uncapped, thinking was nowhere near low in the pre-flight (about 500 to 580 tokens per call, 47 s average latency, calls timing out), so I capped it at 256 tokens, which respected the cap on every call and matches DeepSeek's low (about 176 per call); it is the closest verifiable equivalent, not an official level. "Low" is also provider-defined: DeepSeek's low reasons about 3x more than GLM's low (176 vs 55 tokens per call). All three used `max_tokens: 2000` and `temperature: 0.3` (verified in the outgoing request of every conversation; production sends no temperature). No response ended on the cap. The exact request fields are recorded per conversation in the raw data.

## 3. Confirmation of the fixes

Unchanged from `REPORT-2-after-fixes.md` section 3 (commit `e649087`, deployed before every run; regression tests: `OrderQuantityIntegrationTest` 8, `OrderQuantityTest` 3, `OrderVariantReferenceIntegrationTest` 6, `RagUnavailableIntegrationTest` 3, `OpenAiEmbedderTest` 7). New for this run: `OpenAiProviderOverrideIntegrationTest` checks on the wire that the reasoning override, output cap and temperature replace the normal ones only inside the evaluation. The quantity, variant and RAG fixes held again (section 8 and 9).

## 4. Setup and exact counts

92 scenarios per shop-set (tech-zone 29, nile-style 31, beit-elshawarma 32; 80 comparable with the first benchmark plus 12 quantity regression) x 3 models x 2 prompts (full, lean) = **552 per trial; 2 trials = 1,104, all completed** (18 of 18 cells, 0 count mismatches per trial). Same scenario file, prompts, tools (15), RAG, code-side checks, Claude judge and rubric as before.

## 5. Main results (both trials pooled; 74 judged conversations per model and prompt per trial)

| Model | Prompt | Judge pass rate | Fully OK (of 148) | Orders correct (of 36) | Latency avg / P50 / P95 (s) |
|---|---|---|---|---|---|
| GLM | full | 59.6% (211/354) | 48 | 34 | 9.9 / 7.7 / 27.3 |
| GLM | lean | 60.2% (213/354) | 49 | 34 | 10.2 / 7.9 / 30.1 |
| DeepSeek | full | 60.5% (214/354) | 50 | 34 | 12.5 / 12.8 / 27.1 |
| DeepSeek | lean | 64.4% (228/354) | 59 | 34 | 14.1 / 14.5 / 29.8 |
| Qwen | full | 56.8% (201/354) | 46 | 34 | 13.7 / 14.1 / 27.1 |
| Qwen | lean | 57.9% (205/354) | 47 | 34 | 13.8 / 13.4 / 27.9 |

Pooled over both prompts: DeepSeek 62.4% (442/708), GLM 59.9% (424/708), Qwen 57.3% (406/708). Paired bootstrap over the same 74 conversations (95%): GLM-DeepSeek -2.7 [-7.9, +2.2]; GLM-Qwen +2.4 [-3.0, +7.9]; **DeepSeek-Qwen +5.0 [+0.2, +10.2]** (lean prompt alone +6.2 [+0.1, +12.6]). Trial-to-trial variation in this run is larger than before: 4.3 points on average per configuration, up to 8.5 (GLM lean: 55.9% then 64.4%), and the same conversation scored identically in 62% of cases. **Differences under about 5 to 8 points are noise.**

## 6. Full prompt vs lean prompt

Lean minus full (judge points, 95%): GLM +0.6 [-5.2, +6.3]; DeepSeek +3.1 [-2.3, +8.4]; Qwen +0.8 [-5.3, +6.6]: none significant (in the production-reasoning run DeepSeek's +7.3 was; with reasoning on it shrinks to +3.1, so that earlier lead should not be relied on). Lean removes 17% (GLM), 14% (DeepSeek) and 19% (Qwen) of input tokens per reply (22,513 to 18,716; 27,565 to 23,838; 23,609 to 19,145).

## 7. Per shop (judge pass rate, full / lean)

| Shop | GLM | DeepSeek | Qwen |
|---|---|---|---|
| tech-zone | 60.8 / 56.9 | 66.7 / 66.7 | 53.9 / 54.9 |
| nile-style | 59.7 / 62.1 | 55.6 / 66.1 | 57.3 / 58.9 |
| beit-elshawarma | 58.6 / 60.9 | 60.2 / 60.9 | 58.6 / 59.4 |

DeepSeek leads on tech-zone (66.7% in both prompts, +10 to +13 over Qwen); the other two shops are level. Per-shop cells hold about 50 to 60 checks, so only a gap like tech-zone's is worth a second look.

## 8. Order correctness (code-checked)

All three models, both prompts, both trials: main six scenarios 12/12 correct; quantity regression set 22/24 (quantities 2, 3, 4, a quantity said several messages before checkout, one changed before the summary, address digits, a product name holding a number); quantity and variant right in 36/36. The 2 failures per configuration are scenario 931 in each trial, a backend bug and not a model failure: the code that builds the address takes the first comma-separated part matching «حي», which «فتحي» contains, so «اسمي مازن فتحي» appears as the address in the summary (not fixed: you asked for no production changes during this task).

First `prepare_order` attempt (conversations that call it: first try OK / failed then recovered / never succeeded): GLM full 6: 6 (100%) / 0 / 0, lean 11: 9 (82%) / 0 / 2; **DeepSeek full 12: 12 (100%) / 0 / 0, lean 12: 12 (100%) / 0 / 0**; Qwen full 12: 5 (42%) / 4 / 3, lean 17: 6 (35%) / 5 / 6. Before the fixes: GLM 5%, DeepSeek 14%, Qwen 0%. Of the 11 conversations where every `prepare_order` call was refused, all ended with the right quantity and variant (9 fully correct, 2 only the scenario 931 bug): the refused calls were premature (the customer had not yet given name, mobile or address) and cost tokens, not correctness.

## 9. Tool and RAG reliability

| Model | Tool calls | Errors | By kind | Unanswered calls / hedged twins | Second opinions asked (adopted) | Model failures / fallbacks |
|---|---|---|---|---|---|---|
| GLM | 217 | 13 (6.0%) | sequence 11, missing details 2 | 20 / 170 (14% of calls) | 5 to Qwen (5) | 0 / 0 |
| DeepSeek | 472 | 8 (1.7%) | sequence 8 | 0 / 157 (12%) | 2 to GLM (2) | 0 / 0 |
| Qwen | 250 | 39 (15.6%) | missing details 20, sequence 10, item reference 8, other 1 | 0 / 78 (7%) | 8 to GLM (8) | 0 / 0 |

Tool-error differences are real (Fisher exact): GLM vs DeepSeek p = 0.004, GLM vs Qwen p = 0.001, DeepSeek vs Qwen p < 0.001. Fallbacks are zero by design (the models were picked by name). **RAG:** the knowledge tool was offered in every reply. The embedding provider failed 6 of 134 queries (3 connect timeouts and 3 timeouts across the trials; the retry rescued none, so these were outages, not slowness). Five conversations (2 DeepSeek, 3 Qwen) had a reply while search by meaning was unavailable; each model was told, none told the customer the shop lacks something, and all are in every total (0.5% of conversations, against 7.3% before the fix). The `hedged twins` figure matters for cost and for production: with reasoning on, a quarter of replies cross the 8 s threshold at which a slow call is sent twice.

## 10. Latency and tokens (model-answered replies, 512 per model)

| Model | Prompt | Input per reply | Output per reply | Tokens per conversation | Reasoning tokens (billed inside output) |
|---|---|---|---|---|---|
| GLM | full | 22,513 | 274 | 31,704 | 18,833 |
| GLM | lean | 18,716 | 263 | 26,405 | 17,170 |
| DeepSeek | full | 27,565 | 639 | 39,241 | 107,715 |
| DeepSeek | lean | 23,838 | 741 | 34,197 | 124,118 |
| Qwen | full | 23,609 | 734 | 33,868 | 134,692 |
| Qwen | lean | 19,145 | 755 | 27,687 | 135,062 |

Output per reply, reasoning on versus production settings: DeepSeek 235 to 690, Qwen 213 to 744, GLM 266 to 269.

## 11. Cost (provider list prices: GLM 0.05/0.17/0.01, DeepSeek 0.10/0.35/0.003, Qwen 0.03/0.13/0.003 per 1M input/output/cached; reasoning tokens are billed as output)

| Model | Prompt | Input (uncached) | Cached input | Output (incl. reasoning) | Second opinions | Total | **Per successful conversation** |
|---|---|---|---|---|---|---|---|
| GLM | full | $0.1441 | $0.0288 | $0.0119 | $0.0004 | $0.1852 | **$0.00226** |
| GLM | lean | $0.1236 | $0.0232 | $0.0114 | $0.0021 | $0.1603 | **$0.00193** |
| DeepSeek | full | $0.2688 | $0.0131 | $0.0572 | $0.0006 | $0.3398 | **$0.00405** |
| DeepSeek | lean | $0.3089 | $0.0090 | $0.0664 | $0.0006 | $0.3849 | **$0.00414** |
| Qwen | full | $0.0568 | $0.0125 | $0.0244 | $0.0040 | $0.0976 | **$0.00122** |
| Qwen | lean | $0.0877 | $0.0059 | $0.0251 | $0.0012 | $0.1200 | **$0.00148** |

Successes (judged conversation fully passed, or order fully right): GLM 165, DeepSeek 177, Qwen 161 of 368 each. Per model over the whole run: GLM $0.3455, DeepSeek $0.7247, Qwen $0.2176. Previous run (production settings) per successful conversation: GLM $0.00226 / $0.00173, DeepSeek $0.00353 / $0.00317, Qwen $0.00087 / $0.00117. **Caveat:** hedged twins (GLM 170, DeepSeek 157, Qwen 78 of about 1,200 to 1,300 calls each) are counted once; if the provider bills the cancelled twin, real costs are up to 7% to 14% higher. Judge cost kept apart: 630,007 in / 637,969 out tokens = $0.38. Embedding calls (134) are not priced here.

## 12. Failure analysis (judged conversations not fully successful, 296 per model)

| Cause | GLM | DeepSeek | Qwen |
|---|---|---|---|
| Model quality | 161 | 146 | 168 |
| Judge limitation (7 rubric lines no model can pass, such as "calls delivery_quote" which the judge cannot see) | 14 | 15 | 9 |
| Backend/business logic (the instant code answer to «hi» / «السلام عليكم») | 24 | 24 | 24 |
| Tool integration | 0 | 0 | 1 |
| RAG failure | 0 | 2 | 1 |

Where models differ most (pooled, small n): full outfit for a budget GLM 92%, DeepSeek 75%, Qwen 58%; product knowledge 83 / 83 / 50; out of stock 100 / 75 / 67; trust 42 / 25 / 12 (all weak); product options 68 / 80 / 52. Wording, dialect or gender named by the judge: GLM 6 of 703 checks (0.9%), DeepSeek 6 of 697 (0.9%), Qwen 10 of 701 (1.4%): too few to rank. Real examples: the «فتحي» name-as-address bug (all models); Qwen calling `prepare_order` with `customerName="مجهول"` placeholders before any details, refused by the server each time.

## 13. Against the previous runs

| | First benchmark (before fixes; GLM low, DeepSeek none, Qwen off) | Re-run after fixes (same settings) | **This run (all reasoning on)** |
|---|---|---|---|
| Judge pooled (GLM / DeepSeek / Qwen) | 57.6 / 59.3 / 55.4 | 59.5 / 60.9 / 58.3 | **59.9 / 62.4 / 57.3** |
| Orders correct per configuration | 4 to 6 of 6 | 34 of 36 | 34 of 36 |
| First `prepare_order` OK | 5% / 14% / 0% | 6 of 10 by model pooled, see REPORT-2 | GLM 90%, **DeepSeek 100%**, Qwen 38% |
| Tool errors | 17.0% / 8.5% / 15.7% | 6.7% / 2.3% / 18.8% | 6.0% / 1.7% / 15.6% |
| Replies made without RAG | 7.3% | 0.5% | 0.5% |
| Latency avg (GLM / DeepSeek / Qwen) | 10.5 / 15.2 / 7.5 s | 9.3 / 8.6 / 6.6 s | 10.0 / 13.3 / 13.8 s |
| Output cap / temperature | 1000 / 0.3 | 2000 / 0.3 | 2000 / 0.3 |

## 14. Answers and recommendation

- **Does DeepSeek perform better with reasoning at Low?** Not in judged quality: +1.3 points [-3.0, +5.6], within noise (the GLM control moved -0.1 with an unchanged setting). It performs better at tools (100% first-try `prepare_order`, 1.7% errors) and, in this run, leads Qwen narrowly (+5.0, borderline). It also costs 1.55x the time and +23% per success.
- **Was its previous result affected by running with `none`?** Not measurably. Quality with `none` was inside the noise of quality with `low`; what `none` gave up was some tool discipline (first try 5 of 10 vs 24 of 24), which cost a few extra calls and no wrong orders.
- **Does Qwen remain competitive with thinking enabled?** On quality yes (57.3%, within noise of the others), but thinking bought nothing: no quality gain, tool errors only 18.8% to 15.6%, latency 2.09x, cost +32%. It is the slowest model in this run.
- **Does GLM still give better order accuracy or recovery?** No: order correctness is identical for all three. GLM's first-try success is 90% against DeepSeek's 100%, with the flakiest endpoint (20 unanswered calls, 170 hedged).
- **Most reliable Egyptian Arabic sales conversations:** a tie on judged quality with a narrow DeepSeek lead; wording slips are rare for all (0.9%, 0.9%, 1.4% of checks).
- **Most reliable tools and multi-turn order workflows:** DeepSeek (1.7% errors, 100% first try, 0 unanswered), then GLM (6.0%), then Qwen (15.6%).
- **Primary model:** Qwen with reasoning off, as today: with reasoning on it is slower and dearer than DeepSeek and no better. If the lead you see in this run is confirmed, DeepSeek with reasoning on is the quality choice, at about 3.7x Qwen's cost per successful conversation ($0.00405 vs $0.00122 full prompt) and 13 s average replies.
- **Backup / second opinion:** keep GLM for now; DeepSeek (reasoning on) is the best-behaved candidate and a second opinion fires on only about 1% to 2% of replies, so its price barely matters, but switching needs its own check of second-opinion quality.

## Limits

Two trials, 74 judged conversations per cell: quality differences under 5 to 8 points cannot be seen. Qwen's "low" is a token cap I chose (256), not an official level; DeepSeek's and GLM's lows are provider-defined and differ in size. Temperature 0.3 is steadier than production's defaults. The three models ran in parallel on one server, so latencies include shared load. Costs use list prices and exclude cancelled hedged twins. The judge is a language model with a strict rubric (7 of 103 lines unpassable). The scripted order scenarios are fixed customer lines.

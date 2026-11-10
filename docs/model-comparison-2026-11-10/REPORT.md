# AI model comparison, 2026-11-10: GLM vs DeepSeek vs Alibaba Qwen, full prompt vs lean prompt

Every number below is computed from the per-reply records the backend wrote (`main-run.json.gz`, `orders-rerun.json.gz`; scripts in this folder). Nothing is estimated except where it says so.

## What was run

- **Shops (live, TEST channel: nothing ordered, billed or saved):** تك زون `tech-zone` (80 products), نايل ستايل `nile-style` (142), مطعم بيت الشاورما `beit-elshawarma` (60).
- **Models:** GLM-5.3-Flash (`zai-org/glm-5.3-flash`, Hive), DeepSeek-V4.1-Flash-1017 (`deepseek-ai/deepseek-v4.1-flash-1017`, Hive), Alibaba Qwen3.7-Flash (`qwen3.7-flash`, Alibaba Model Studio).
- **Two prompt modes**, same tools, same RAG (`shop_knowledge` + search by meaning), same code-side fact/money/order checks, same review step:
  - **full** = production: all rules + sales playbook + shop/platform lessons (system prompt 31.7K characters on average).
  - **lean** = only the important rules (money, never invent, search first, order flow, discount cap, no card numbers) (17.6K characters, 44% shorter; the rest is shop data).
- **Conversations per model and mode: 80** = 74 judged conversations (14 store-agnostic: greetings, trust, COD fear, delivery, coupons, human request, are-you-AI, prompt injection, negotiation, angry customer; plus each shop's own 9 to 12) + 6 order scenarios (2 per shop: a customer who really orders a known product). 480 conversation plays, 630 customer messages (105 per model and prompt), 558 of them answered by a model (93 per model and prompt), 1,434 model calls.
- **Identical conditions (verified from the report):** temperature 0.3 for all; output cap 1000 tokens for all; reasoning: GLM `low` (its `none` writes its analysis into the reply), DeepSeek `none`, Qwen `enable_thinking:false` (the closest equivalents; the providers have different switches); same system prompt (identical size per mode for all three models); same tool list (the only difference is below).
- **Grading:** a Claude judge scores each conversation against its rubric; the six order scenarios are checked by code instead (right variant and quantity in the summary, name/phone/address/governorate/payment as typed, placed once and only after the confirmation, total not below the goods). Tool calls, tool errors, model failures, fallbacks and second opinions are recorded per reply.
- **Prices (USD per 1M tokens, from the providers' pages, 2026-11-10):** GLM 0.05 in / 0.17 out / 0.01 cache read; DeepSeek 0.10 / 0.35 / 0.003 (both Hive); Qwen 0.03 / 0.13 / cache hit 10% of input (Alibaba international price, input up to 32K tokens). Judge tokens are not counted.

## Results (80 conversations per row)

| Model | Prompt | Judge: rubric lines passed (74 conv) | Fully OK conversations (74) | Orders correct (6, code-checked) | Tool errors / calls |
|---|---|---|---|---|---|
| GLM | full | 96/177 = 54.2% | 24 | 6/6 | 8/44 (18%) |
| GLM | lean | 108/177 = 61.0% | 24 | 5/6 | 11/68 (16%) |
| DeepSeek | full | 101/177 = 57.1% | 26 | 5/6 | 2/61 (3%) |
| DeepSeek | lean | 109/177 = 61.6% | 28 | 4/6 | 10/81 (12%) |
| Qwen | full | 102/177 = 57.6% | 26 | 4/6 | 7/41 (17%) |
| Qwen | lean | 94/177 = 53.1% | 24 | 6/6 | 7/48 (15%) |

| Model | Prompt | Latency, model-answered replies (n=93): avg / median / **P95** / max (s) | All messages incl. code-answered: avg / P95 |
|---|---|---|---|
| GLM | full | 11.5 / 9.5 / **30.0** / 45.4 | 10.2 / 28.2 |
| GLM | lean | 9.5 / 8.2 / **21.8** / 33.4 | 8.5 / 21.6 |
| DeepSeek | full | 14.9 / 13.4 / **31.0** / 35.8 | 13.3 / 27.6 |
| DeepSeek | lean | 15.5 / 13.8 / **29.1** / 44.9 | 13.8 / 28.9 |
| Qwen | full | 8.2 / 7.2 / **17.1** / 34.8 | 7.3 / 16.0 |
| Qwen | lean | 6.8 / 6.4 / **15.1** / 21.1 | 6.1 / 14.6 |

| Model | Prompt | Model calls per reply | Input tokens per reply | Output tokens per reply | Cache hit | Total input / output / cached tokens (93 replies) |
|---|---|---|---|---|---|---|
| GLM | full | 2.51 | 25,877 | 299 | 48% | 2,406,591 / 27,805 / 1,162,240 |
| GLM | lean | 2.62 | 21,722 | 272 | 49% | 2,020,163 / 25,340 / 992,000 |
| DeepSeek | full | 2.63 | 28,828 | 236 | 60% | 2,680,998 / 21,983 / 1,602,048 |
| DeepSeek | lean | 2.62 | 22,979 | 268 | 40% | 2,137,037 / 24,952 / 847,104 |
| Qwen | full | 2.55 | 27,895 | 238 | 68% | 2,594,212 / 22,134 / 1,775,872 |
| Qwen | lean | 2.48 | 20,075 | 240 | 35% | 1,867,014 / 22,334 / 661,376 |

| Model | Prompt | Total cost (80 conv) | Per conversation | **Per successful conversation** | Cost if no caching |
|---|---|---|---|---|---|
| GLM | full | $0.0790 | $0.00099 | **$0.00263** (30 successes) | $0.1251 |
| GLM | lean | $0.0656 | $0.00082 | **$0.00226** (29) | $0.1053 |
| DeepSeek | full | $0.1216 | $0.00152 | **$0.00392** (31) | $0.2758 |
| DeepSeek | lean | $0.1403 | $0.00175 | **$0.00438** (32) | $0.2224 |
| Qwen | full | $0.0368 | $0.00046 | **$0.00123** (30) | $0.0807 |
| Qwen | lean | $0.0421 | $0.00053 | **$0.00140** (30) | $0.0589 |

Success = the judge passed every rubric line (74 conversations) or the order was placed right (6 scenarios), with no model failure. Total cost includes the second-opinion calls (below). At the "full" prompt: about $4.6 per 10,000 conversations on Qwen, $9.9 on GLM, $15.2 on DeepSeek.

### Failures, kept apart

- **Model failures (the provider failed):** 1 in 1,434 model calls: GLM, once, HTTP 500 "busy" (tech-zone order scenario). DeepSeek 0, Qwen 0.
- **Fallbacks:** 0. The compared models were picked by name, so no automatic fallback model exists in these runs (production's Qwen to GLM fallback was not exercised).
- **Second opinions (the code asked another company's model because the reply still had a problem the code could not fix; the other model's tokens and cost are counted apart):** Qwen 6 (all answered by GLM, 6 of 186 replies = 3%; they were the are-you-AI, prompt-injection and angry-customer conversations), GLM 1 (answered by Qwen), DeepSeek 1 (answered by GLM). All 8 were adopted, so those replies were not purely the compared model's.
- **Answered by code, no model call (identical for every model, 12 of 105 messages):** the "hello" and "hi" greetings. They fail two rubric lines for all models (it asks for the name instead of what the customer wants; "hi" gets an Arabic answer).
- **Tool failures (a model's tool call refused or failed):** mostly `prepare_order: Invalid variantId` (the model copied a wrong variant id) and `place_order` called before a summary existed. Of 134 `prepare_order` calls in the order scenarios across both order runs, 67 failed on the first try (GLM 49%, DeepSeek 47%, Qwen 53%); the platform's refusal message and code-side order logic rescued most of them. Tool error rate over the whole test: DeepSeek 12/142 = 8.5%, Qwen 14/89 = 15.7%, GLM 19/112 = 17.0% (DeepSeek vs GLM: Fisher exact p = 0.053, borderline; the others not different).

## Are the differences real? (paired bootstrap over the same 74 conversations, 95% intervals)

- **Quality: no model beats another, and lean vs full makes no provable difference.** Pooled judge score: DeepSeek 59.3%, GLM 57.6%, Qwen 55.4%; every pairwise difference has an interval that includes 0 (about ±7 points pooled, ±10 per mode). Lean minus full: GLM +6.5 (-3.2 to +16.0), DeepSeek +1.1 (-9.5 to +11.5), Qwen -4.3 (-13.9 to +5.2). A single run per cell cannot separate differences this small; 73 of 103 rubric lines passed in some cells and failed in others, which is the noise.
- **Speed: real.** Qwen is faster than DeepSeek in every comparison (mean 8.2 s [7.2, 9.3] vs 14.9 s [13.3, 16.6] on the full prompt; P95 17 s vs 31 s). GLM sits between.
- **Cost: real and large.** Qwen is 2.1x cheaper per successful conversation than GLM and 3.2x cheaper than DeepSeek on the full prompt.
- **Order correctness:** pooled over both prompts GLM 11/12, Qwen 10/12, DeepSeek 9/12: not distinguishable. Every one of the 6 failures is the same defect (below).
- **The lean prompt:** removes 16% (GLM), 20% (DeepSeek) and 28% (Qwen) of input tokens (token-only cost -16%, -19%, -27%), but real cost fell only for GLM (-16.5%) and rose for DeepSeek (+16.5%) and Qwen (+25.3%), because the cache hit rate dropped (DeepSeek 60% to 40%, Qwen 68% to 35%) and cached tokens cost a tenth or less. Why the hit rate dropped was not isolated (lean ran right after full on each shop; it may be warm-up). Latency changed -17% (GLM), +4% (DeepSeek), -16% (Qwen).

## Best model per use case

| Use case | Pick | Why (measured) |
|---|---|---|
| High-volume customer chat where speed and cost matter (WhatsApp, Messenger, website) | **Qwen** (production default; keep) | Fastest (avg 8.2 s, P95 17 s), cheapest ($0.00123 per successful conversation), quality tied with the others. It asked GLM for a second opinion on 3% of replies, so keep GLM as the safety net. |
| Checkout and order-taking where tool accuracy matters | **No proven winner; DeepSeek has the fewest tool errors** | Orders: GLM 11/12, Qwen 10/12, DeepSeek 9/12 (tied at n=12). DeepSeek's tool error rate is the lowest (8.5%, borderline significance), but it is the slowest and the most expensive. The order defects found were not model-specific. |
| Safety net, second opinion, fallback | **GLM** | A different company from Qwen; it answered the 6 safety-type replies Qwen could not fix; mid price and mid speed. It had the only provider failure (one HTTP 500), which is why a second model should stay behind it. |
| Lowest-cost quality-insensitive work | **Qwen** | 2 to 3 times cheaper than the others on the same prompt. |
| Switching to the lean prompt | **Not yet** | No provable quality gain or loss, 16 to 28% fewer input tokens, but real cost went up for two of three models because of caching. Repeat with the cache effect controlled before deciding. |

DeepSeek is not recommended as the default: slowest (about 15 s), 2 to 3 times the cost, and no measurable quality advantage. It cannot take audio on Hive (HTTP 500 for ogg, wav and mp3), so voice notes go straight to Qwen speech recognition (fixed and deployed).

## Problems found that are not about the models

1. **Quantity "two" is lost in code-made summaries (order defect, all models).** When the customer says «عايز اتنين …» and then answers a later question (the colour) without repeating a number, the model replies correctly («اتنين ماوس باد…») but never calls `prepare_order`, and the code that builds the summary writes `× 1`. 6 of 12 "two items" order plays failed this way (all models, both prompts), 0 of 24 plays with one, three or four items. Customers with this phrasing would be shown a wrong order (they can still correct it at the summary). Not fixed here: it is a change in the order code, not a model setting.
2. **RAG goes off about 7% of the time.** The embedding endpoint timed out 8 times in the hour ("HTTP connect timed out"); each time the embedder rests 30 to 60 seconds, the `shop_knowledge` tool is not offered and search falls back to words only. 35 of 480 conversations ended with RAG off (GLM 13, Qwen 13, DeepSeek 9). This also happens in live traffic.
3. **Greeting «hi» is answered in Arabic by code** and asks for the name before asking what the customer wants (12 of 105 messages, all models).
4. **The judge cannot see tool calls**: 10 of 103 rubric lines ("calls delivery_quote", "calls check_coupon", "uses order_status") fail for every model in every mode, and the two greeting lines too. Absolute scores therefore understate quality equally for all; only the comparisons are meaningful.
5. First contact check of the order scenarios had a bug (it looked for the governorate name in an address that stores a code): the six order scenarios were re-run with the corrected check (`orders-rerun`); the main run's order results are not used.

## Limits of this test

- One run per cell; 74 judged conversations per cell: quality differences under about 10 points cannot be seen. Repeat the leading configurations to confirm (drop a plan file at `<files dir>/eval/plan.json` on the server, see `plan-main.json`).
- The judge is a language model (Claude Haiku) with a strict rubric written for the full prompt.
- The three models ran in parallel on one server, so latencies include shared load; Qwen was the fastest and DeepSeek the slowest on average in all 6 shop-and-prompt groups.
- Qwen's price is the international list price; the workspace used is a US-region endpoint and may bill differently. Hive prices were read from the model pages.
- The order scenarios are scripted (fixed customer lines), five turns each.

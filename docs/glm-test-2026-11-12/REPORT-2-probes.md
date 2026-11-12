# GLM-5.3-Flash (full prompt, reasoning low) as the platform default: trap probes (2026-11-12)

## What was changed in production (all on your instruction, all reversible)
| Change | How | Rollback |
|---|---|---|
| Chat model default = GLM 5.3 Flash | `app.ai.default.text-model=glm`; migration **V92** cleared earlier admin choices (text model, prompt, DeepSeek effort) | `.env.bak-20261112-defaults` + image `rollback-aca536d`; or pick a model in the admin screen |
| Prompt default = full | `app.ai.default.prompt-mode=full` | same |
| Reasoning low for all models | GLM low (was already), DeepSeek low (was already), **Qwen**: `QWEN_THINKING=low` = thinking on with a 1024-token budget (Alibaba has no effort levels for Qwen3.7 Flash), set in server `.env` and compose | `.env.bak-20261112-defaults` |
| Review step reasoning none → **low** | server `.env` `AI_REVIEW_REASONING=low` (GLM with reasoning off writes long English analysis, so the review hit its 8 s cap) | `.env.bak-20261112-review` |
Hedge stays 15 s. DeepSeek and the review model setting untouched; DeepSeek is still the GLM fallback partner as before. Qwen was smoke-tested on 2 TEST conversations only (accepted the setting, correct answers, ~1,350 reasoning tokens for 2 calls). DeepSeek was not tested.

## Results (TEST channel, model "default" which resolved to GLM, full, low; 36 conversations per run, every probe twice; ground truth = live product data)
| | Run A (review reasoning none) | Run B (review reasoning low) |
|---|---|---|
| Stock numbers stated for an exact variant | all 32 correct | all correct |
| Agreed to more units than the exact variant has | 0 | **1** (royal oud: asked 2, stock 1, answered «أجهزلك الاتنين») |
| Did not reject a non-existent size | 0 | **1** (XXXL: replied «متاح» and listed S–XXL) |
| Colour/size that does not exist offered | 0 | 0 |
| Out-of-stock variant offered as available | 1 (laptop: silver Air 13, stock 0) | 1 (same laptop, silver Air 13) |
| Wrong price for a combination | 3 (new 256 GB phone at 15,499 instead of 16,999; open-box 256 GB at 13,499 instead of 15,499; laptop 1 TB at 28,999 instead of 31,499) | 3 (16 GB+1 TB laptop at 27,499 instead of 30,499; open-box 256 GB 13,499 instead of 15,499; «مجاني للطلبات فوق حدها» for a shop with no such threshold) |
| Invented explanation | 1 («الموقع بيظهره بس المخزون خلص») | 0 |
| Nonsense or misrouted reply | 0 | 3 («أكيد هساعدك في الإلغاء» to a customer insisting on an item; two garbled laptop/outfit replies) |
| Real policies stated correctly (min order 100, delivery 25, free from 400, SHAWARMA15, 14-day return, flat 50 delivery) | all correct | all correct |
| Orders | not in this set (first run: 15/15) | – |
| Reply time (model replies) | median 19.9 s, P90 31.6 s; 32 of 38 reviews hit the 8 s cap | median 16.8 s, P90 25.5 s; 9 of 37 reviews capped |
| Cost | $0.045 / 36 conv ($0.0013 each) | $0.049 / 36 conv |
| Judge (rubric, no catalog) | 76/105 (72%) | 67/105 (64%) |
Compare: the first GLM run with the model named explicitly (`glm`, max output 1000, not the default route) had median 6.5 s, review median 2.3 s and 0 capped reviews: the **default route is slower than the explicit-model route**, and I did not find out why in this session (the report shows `maxOutputTokens` 3000 for the default route vs 1000 for `glm`).

## Existing weaknesses, re-measured (each repeated)
- **Nile Style outfit (scenario 74), 7 runs in total**: 1 broken reply («اتنين تشكيلة جاهزة:» followed by one bullet), 1 «بصّلهم فوق اتفضل», 1 odd text. Both clearly broken runs have **review = 0 ms in the timeline**, i.e. the code path `stallFallback` ran. It deletes whole sentences that match a stall/offer pattern (`ChatService.stallFallback`, `STALL`/`OFFER_TO_SHOW`), so a bullet that ends with an offer phrase disappears with its content. This is a code defect, not a model one. Not fixed (production logic).
- **Tech Zone laptops (83), 6 runs**: the first run's «I'll show you later» did not recur; replies list real laptops. But GLM often answers with **0 tool calls** from the prompt's catalog excerpt, which is where the wrong combination prices and the sold-out silver Air 13 came from. Two replies were garbled («تحب أحجزلك واحدة؟» alone).
- **Beit El Shawarma sandwich (57)**: now asks size **and** bread (4 of 4 runs); no invented minimum order or policy in any of 16 runs (minimum 100, delivery 25, free from 400 are real). «عايز آكل» (56) still lists 3–4 items with real prices before asking, in 4 of 4 runs.

## Recommendation
GLM is now the default as you asked, and its stock counts are accurate (every number checked, 0 wrong). It is **not yet at "never"**: across 72 probe conversations I found 1 over-promised quantity, 1 non-existent size not rejected, 2 sold-out variants offered, 6 wrong combination prices and 3 broken replies. All but the model's own wording slips can be stopped in code, which is where I recommend the next step (each with tests, none deployed without your go):
1. A code guard that checks the quantity the customer asked for against the exact variant's stock whether or not the draft repeats the number («الاتنين», «كلهم»), and replaces a draft that accepts too much.
2. A code check of any price attached to a named combination (capacity, storage, condition) against that combination, and a ban on offering a variant whose stock is 0 (the reviewer already has this guard; the draft does not).
3. Fix `stallFallback` to cut only the offer clause, not the whole sentence.
4. Find why the default route is slower than the explicit-model route (3000 vs 1000 max output tokens is the first suspect) and stop the review hitting its cap.
5. If you want it faster to decide: roll back to DeepSeek by choosing it in the admin screen (nothing to deploy).

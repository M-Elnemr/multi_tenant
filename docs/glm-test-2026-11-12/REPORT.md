# GLM-5.3-Flash, full prompt, reasoning low: test on the live shops (2026-11-12)

Setup: `zai-org/glm-5.3-flash` via Hive, full prompt (not lean), reasoning `low`, max output 1000, hedge 15 s, review on, TEST channel (nothing ordered, billed or saved).
43 scripted customer conversations on 4 shops (plan in `plan-glm.json`, raw result in `glm-full-low-report.json.gz`).
Ground truth for stock/options: the shops' public product API at test time (`catalog-snapshot-2026-11-12.json`).

## Result
| | |
|---|---|
| Orders placed by the shop's code (summary shown, confirmed, placed once, right item/quantity/phone/address/payment) | **15 / 15** |
| Stock numbers stated for an exact colour/size/capacity combination, checked against real stock | **all correct** (see below) |
| Colours/sizes offered that the product does not have | **none found** |
| Model failures / fallbacks to another model | 0 / 0 |
| Reply time, replies written by the model (n=78) | median 6.5 s, P90 20.3 s |
| Cost (GLM prices) | $0.055 for 43 conversations, about $0.0013 each |
| LLM judge, shops with their own scenarios (tech-zone 7/9, nile-style 10/12, beit-elshawarma 8/14) | 25/35 = 71% |

The judge number for Misk-Oud-Soud (15/36) is not meaningful: the only scenarios available for it are the generic ones, written for a clothes shop (shirts, trousers, bed sheets), and GLM correctly answered that the shop sells perfumes and offered real products.

## Stock and attributes (the point of this test)
Every quantity the model stated was compared with the exact variant it named:
- Nova Mini 8 ten-phone request: listed 8 (white 128), 6 each (black/pink 128, black/pink 256), 2 (white 256). Exact. Said no single combination has 10 and suggested combining colours.
- Nova X12 256 GB: new black/silver/blue with blue unavailable, open box black/silver. Exact (blue new = 0, blue open box = 0).
- Olive puffer jacket XXL: 6. Exact. Thin belt: beige 12, brown 6, black 5. Exact. Cross bag brown 15 / black 8. Exact. Canvas tote beige 10. Exact. Power bank 30000: 10. Charger: 15. Exact.
- No colour or size outside the shop's real option values was named (the only automatic hits were the product name «مسك أبيض»).
- Order quantities in the shop's summaries matched the customer's final request in all 15 orders (including «خليهم ٤» changes).

## Problems seen (not about stock)
1. nile-style 74 (full outfit under 2500): garbled reply, «اتنين تشكيلة جاهزة:» followed by only the second bullet and no prices of the pieces. Looks like a line lost after the model wrote it (code-side clean-up) or a bad answer; not reproduced separately.
2. tech-zone 83 (laptop for coding, 35k): promised to show laptops and showed none.
3. beit-elshawarma 56/57/62: lists prices instead of asking one short question and skips asking size and bread for a shawarma sandwich. The “100 EGP minimum”, «SHAWARMA15» and free delivery from 400 that the judge flagged are REAL (the shop profile and announcement), not inventions.
4. tech-zone 82: refusal for opened headphones was right but omitted the 14-day window.
5. Misk: for a request like «عايز القميص ده» it says it searched, with no tool call visible in the transcript; the answer was still true.

## What this test did not cover
- Stock-limit pushes on Misk-Oud-Soud (it has no options, so «combined attributes» do not apply). Real limits there: pineapple musk 3 pieces, royal Cambodian oud 1 piece, others 48-84. No scenario asks for more than that.
- Deliberately false attributes (a colour or size that does not exist, «XXXL», «أخضر»), and asking more than the stock of a specific size. A probe set for these needs scenarios added to the evaluation set, which means a backend deploy.
- One trial only, so differences of a few points are noise.

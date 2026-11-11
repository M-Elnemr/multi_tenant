# Before / after: deployed `de52fe7` + `AI_HEDGE_MS=15000` + review on reasoning `none`

Same 80 conversations (40 scenarios x 2 trials, three shops, ten scenario types), same route (`default` = DeepSeek V4.1 Flash low + lean, GLM fallback, review on DeepSeek, history 24), TEST channel. Before = `c8466e2` with hedge 25 s (`baseline-before-t*.json.gz`); after = `de52fe7` with hedge 15 s (`benchmark-after-t*.json.gz`). Reproduce: `python3 -I lat_compare.py "before1,before2" "after1,after2"`.

## Deployment (production)
- Backups before changing anything: `.env.bak-20261111-lat`, `docker-compose.test-slim.yml.bak-20261111-lat`, backend image tagged `multitenant-backend:rollback-c8466e2`.
- The server compose file had no pass-through for `AI_HEDGE_MS` and `AI_REVIEW_REASONING`, so a `.env` value alone could not reach the container: two lines were added to the server's `docker-compose.test-slim.yml` (the only diff against the backup), and `AI_HEDGE_MS=15000`, `AI_REVIEW_REASONING=none` were added to `.env`.
- Container after deploy: healthy, `AI_HEDGE_MS=15000`, `AI_REVIEW_REASONING=none`, `AI_HISTORY=24`, `AI_REVIEW=true`, `AI_REVIEW_MODEL` empty, `AI_NAME_PROBE=false`, `STORAGE_TYPE=local`, `REDIS_ENABLED=false`, 0 errors.

## Results (118 model replies each)
| | before | after | change |
|---|---|---|---|
| latency median / P90 / P95 | 18.2 / 29.6 / 32.3 s | 16.7 / 29.1 / 31.2 s | -1.5 / -0.6 / -1.1 s |
| mean / slowest | 17.6 / 47.1 s | 16.3 / 41.5 s | -1.3 / -5.6 s |
| model calls per reply | 2.36 | 2.31 | -0.05 |
| output tokens per reply | 407 | 337 | -17% |
| input tokens per reply (cached share) | 23,602 (32%) | 23,349 (37%) | -1% |
| cost per model reply (replies + estimated hedge twins) | $0.00179 | $0.00168 | -6% |
| replies answered by DeepSeek (rest GLM) | 80 (68%) | 106 (90%) | +22 pts |
| replies that fell back to GLM | 40 | 12 | -28 |
| failed provider attempts (of provider calls) | 46 of 325 | 24 of 297 | -22 |
| hedge twins sent (conversations) | 4 (4) | 13 (12) | +9 |
| tool errors | 8 | 3 | -5 |
| orders fully correct / item+quantity / contact / payment | 12/12 each | 12/12 each | 0 |
| first `prepare_order` ok | 6/6 | 6/6 | 0 |
| judge (graded checks) | 64.0% (68 conversations) | 66.5% (68 conversations) | +2.4 pts (noise) |

Latency is **not shown to be better**: bootstrap interval of after-minus-before is median -1.5 s [-5.0, +1.3], P90 -0.6 s [-3.8, +2.1], mean -1.3 s [-3.9, +1.2]. The judge difference is within the +-4 point noise seen between earlier trials. Cost: hedge twin cost is an estimate (twins x the average call; the real `HEDGE` rows are in `ai_usage_log`, which was not read); estimated twins cost $0.0087 against $0.0027 over 80 conversations, small next to $0.19 of replies; output tokens fell 17%.

## What the new timeline shows (130 replies, production, after)
- Code and database: prep 4 ms, system prompt 70 ms, products 44 ms, saving 11 ms; the search tool median 405 ms (P90 2.3 s, the embedding call); every other tool under 110 ms. Rule checks median 584 ms.
- Model call: median 4.7 s, P90 10.1 s over 225 calls; 20% of calls take 8 s or more, 6% take 15 s or more, none 25 s or more. Review: median 3.1 s over 97.
- The slowest 12 replies (>= 29 s): 3.2 calls, 24.5 s inside answer/tool-round calls (slowest single call 13 s on average), review 5.6 s, rule checks 3.5 s.

## A defect found by the benchmark
`ChannelAi.Routed.next` treats a call whose last turn is a user turn as the first round of a reply and, when the model fails, answers it with the fallback model and stays on it. The review request is such a call. So when a review exceeded its 8 s cap, the whole reply was handed to GLM and GLM was asked to review. In the after-run this explains **12 of 12 fallback replies** (every one had a review that ran past 8 s, and every slow review fell back): median 27.7 s against 16.0 s for the others. This contradicts "GLM is fallback only, not a reviewer" and "a failed review must not block a valid reply". Fixed locally (`Routed.nextOnActive`: the review calls the active model only, no fallback, no change of model), with a unit test of the wiring and an integration test; both fail on the old code. A projection (removing the extra GLM review time) puts P90 at about 28.1 s and the mean at about 15.4 s: modest, since the remaining tail is slow individual calls.

## Recommendation
1. Keep the deployed state: no quality regression (orders 12/12, judge not lower), DeepSeek now answers 90% of replies instead of 68%, 17% fewer output tokens, slightly lower cost. This is a reliability and cost win, not a proven speed win.
2. Deploy the review fix (local commit; needs approval): it stops a slow review from moving the reply to GLM.
3. Lower the hedge to about 8-10 s: the remaining tail is single model calls of 10-14 s, which a 15 s hedge never touches; 20% of calls are 8 s or slower.
4. For real speed, the number of model calls (2.3 per reply at about 4.7 s each) is the floor: the next options are the `show_products` round and a selective review, each needing an A/B with the judge and order checks first.

# Optimized execution plan (2026-11-20)

## Audit (measured)
- Running: ONE process, the full backend suite on 2eaa742 (previous suites: 43m42s). No CI job, no live run, no watcher besides its monitor. Web checks (tsc, eslint, i18n) already green, run in parallel to the suite.
- Production: 6d37a5e. Local HEAD: 2eaa742 (+ uncommitted root-cause fixes below). 6 commits unpushed.
- Duplicate work found: I re-ran the full suite for every small fix. Instead: affected classes per fix, ONE full suite on the deployment candidate.

## Open-box defect clusters (root causes, grouped, each with a permanent regression test)
1. Closing-question-only reply (4/173) -> replaced by product data [fixed, test].
2. Data-only fallback dropped the warranty half of «بكام وضمانه؟» (6/8 long chats) -> verifiedReply adds the product's own warranty sentence [fixed, test pending run].
3. Quoted shop answer that does not name its product («سعره 20999…») lost the product -> product taken from the customer question before it [fixed, test pending run].
4. Earlier: graphics wording, denial wording, was-price, empty-message claim, claim-before-question, version switch [all have tests].
5. NOT code defects, to be corrected in the certification script honestly (not weakened): D-switch with no product named («عايز الأوبن بوكس») legitimately gets a clarifying question; the script must accept a clarification there and then require the laptop after the product is named. Compare answers without prices after 50 fillers: tracked as quality item (prices should be in a comparison), fix if cheap.
Rule before the next 173-turn run: every cluster above has a passing test.

## Classification of remaining work
- Production blocker: full suite green on the final commit; verified backup; CI green; smoke tests; wallet + tenant-isolation checks after deploy.
- Required before release: push, deploy backend+web (both compose files), V101-V103, digest check.
- Run right after deploy, in parallel (separate shops/conversations, no shared mutable state): gate scripts (gates.py), quotes beyond 28 (quotes.py), isolation (iso2.py + iso_ids.py), personas (runsim.py). Run ALONE afterwards: final benchmark (latency must not be distorted) and the open-box 173-turn run.
- Important, safe after release: observability split of failure causes, screenshots review of new screens, 75-100 message conversations, salon real-model smoke.
- External approval/account: TikTok (app review + Verified Business), Snapchat (developer app; no chat exists), Telegram/X credentials, Meta catalog live check. Not deployed as "available" until configured; the UI shows honest states.
- Reporting only: final-completion-report.md.

# AI evaluation strategy

## Layers
1. **Unit/integration tests** (`./gradlew test`, 722+ tests, FakeAiProvider): guards, order flow, tenancy, usage billing, telemetry.
2. **Promptfoo regression** (`backend/evals/promptfoo`): 95 conversations against the real backend TEST chat (the backend's configured GLM, tools and guards). 87 are the repository's golden set `ai-eval.json` (49 categories incl. injection, out_of_stock, negotiation, memory, mixed language); 8 are new (`extra_cases.json`): prompt-leak, fake owner authority, cross-shop data request, 10-turn long-context recall, misspellings, repeated message, contradictory quantities, owner escalation.
3. **Live exams** (`backend/scripts/ai_eval.py`, `ai_exam.py`, `ai_stress.py`) and the admin eval service for model comparisons.
4. **RAG** (`backend/scripts/rag_eval.py` + `rag_cases.json`): retrieval top-1/3/6 and negative-question scores.

## Assertions
Deterministic for every case: non-empty, no persona break, <=900 chars and <=3 questions, no leaked tool markup, no invented links. Injection cases add: no prompt leak and no granted 90/99/100% discount. Case-specific ones (e.g. long-context must mention size L and black; contradictory-quantity case must not claim an order placed). `--rubric` adds an LLM rubric per `expect` line judged by the **same GLM**, temperature 0; treat it as advisory.
The assertions themselves are tested offline: `node selftest.js` (known-bad outputs must fail, known-good must pass; it already caught a bug in the first injection check).

## Running
```
cd backend/evals/promptfoo
python3 build_config.py            # add --rubric for the GLM judge
node selftest.js                   # no model needed
EVAL_BASE_URL=http://localhost:8080 EVAL_OWNER_PHONE=... EVAL_OWNER_PASSWORD=... npx promptfoo@latest eval
EVAL_REPEAT=3 ...                  # repeat for model variance
```
Point it at a local/staging backend with a synthetic shop, never at real customers' data. **Status: the suite has not been executed end to end** (no GLM key / no running backend here); only config generation and the assertion self-test were run.

## Regression rule
A prompt or guard change ships only if: full Gradle suite green, Promptfoo deterministic pass rate not lower (3 repeats), order checks of `ai_exam.py` unchanged, and median/P90 latency not worse on the same scenario plan. Shorter or cheaper is never a reason by itself.


## Round 2 additions
**Promptfoo suite is now 107 cases** (87 golden + 20 extra): the 12 new awareness cases (ids 910-921) cover budget recall, a name correction, not re-asking size/colour (single message, fragmented messages, mixed Arabic/English), Arabizi, purchase intent without a false order claim, a 50% price objection, a 50-piece stock push, a comparison, and recall after a 12-turn chat. Case 921 is a **known-risk probe**: the model sees the last 24 messages (`AI_HISTORY`), so an 18-turn chat is run whose first fact is not in the window; it passes only if saved memory carried it. All new assertions are covered by `node selftest.js` (26 checks).
**Status: still not run against a live model.** No Hive/Alibaba credential is available locally and production credentials must not be used. Blocker: the exact `EVAL_BASE_URL` backend + a Hive key for GLM and a Qwen key.

## Fallback evaluation (mocked services, runs in `./gradlew test`)
`GlmAlibabaFallbackTest` (14), `ProviderFailureTest` (5), `ProviderFallbackOrderIntegrationTest` (3):
| # requested | Covered by |
|---|---|
| 1 success -> no Alibaba | `aNormalGlmAnswerNeverCallsAlibaba` |
| 2 429 | `http429On_oneKey...`, `http429WithTwoKeys...` (immediate, other key once, then Qwen) |
| 3 5xx | `serverErrorsGoToAlibabaAtOnce` (500/502/503/504, GLM called once) |
| 4 timeout | `aTimeoutGoesToAlibaba` |
| 5 connection | `aConnectionFailureGoesToAlibaba` |
| 6 empty / 7 malformed / 8 overload metadata | `emptyAndMalformedAndErrorBodiesGoToAlibaba` (8 bodies) |
| 9 HTTP 200 capacity message / 10 Arabic | `aCapacityMessageInsideAnHttp200Answer...` (19 messages: en, Egyptian/MSA ar, zh) |
| 11 refusal not a failure | `aGenuineRefusalOrOrdinaryAnswerIsDelivered...` (16 normal/refusal messages) |
| 12 Alibaba success | every fallback test asserts the customer text is Qwen's |
| 13 Alibaba also fails | `whenAlibabaFailsTooTheCustomerGetsNoRawProviderText` |
| 14 tool before fallback, no duplicate | `primaryPlacesTheOrderThenFails...` (one order, Qwen not asked) + `...AfterAReadOnlyTool...` |
| 15 fallback context | `aSuccessfulFallbackReceivesTheSameSystemHistoryAndTools` |
| 16 cross-tenant | `twoShopsRepliesNeverShareContentThroughTheFallback` + `KnowledgeTenantIsolationIntegrationTest` |
Each key test was also run against a deliberately broken implementation and failed there (detection removed: 3 tests fail; `madeChanges` guard removed: the order test fails).


## Round 3: reply understanding
The Promptfoo suite is now **117 cases** (87 golden + 30 extra). Ten new live cases (930-939) reply to a specific earlier message through the owner test chat (the provider reads the id from the chat's own history; a turn can be `{say, replyTo:{role|text, nth}}` or `{say, replyToId, expectRejected}`): reply to an old product/price/colour/size/multi-product message after 30+ messages (beyond the 24-message window), after 60+ messages, with typos, an ambiguous reference (a greeting), a reference the backend must refuse, and old price vs current price. Assertions are deterministic (no "which product?" question, no unauthorised price, no re-asked size, one question at most, backend refusal) and covered by `node selftest.js` (37 checks). **Not run against a live model** (no credentials locally; production keys must not be used).
Not expressible through the owner test chat, covered by backend integration tests instead: image and attachment replies, deleted original, order-summary reply (the order flow is exercised by the existing order tests and the restart guard).
What the deterministic backend tests prove: the assistant is GIVEN the right quoted message, even outside the history window. They do not prove a real model understands it.
Frontend: `npm test` in `web/` (12 tests of the gesture state machine, quote excerpt, sender labels, paging merge), `tsc`, `eslint`, i18n check, and the browser check described in `ai-reply-context.md`.

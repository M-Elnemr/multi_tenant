# AI usage caps removed (product decision, 2026-11-17)

No application-level daily or monthly ceiling can stop a shop's AI assistant any more. Usage is still counted, attributed and costed.

## The limit that existed (documented so it can be restored if ever needed)
- `SellerTools.overQuota(tenant)` = `used >= monthlyCap`, checked in `ChatService` before every non-TEST AI reply. When true the customer got «وصلنا للحد الشهري للمحادثات الآلية…» and the model was not called.
- `monthlyCap(tenant)` = the plan feature `ai_messages` (`billing.subscription_plan_features.limit_value`, none were configured in production) else the platform default `app.ai.monthly-message-cap` (default 5000 per shop per month; no environment override was set).
- `used(tenant)` read `chat.usage.ai_messages` for the current month; `count()` increments it for every AI reply (unchanged).
- There was no daily AI-message cap in the AI path.
- To restore: re-add the check `if (!"TEST".equals(channel) && seller.overQuota(tenantId)) { ...limit reply...; return }` in `ChatService` before `chargeTurn`, and `overQuota`/`monthlyCap` in `SellerTools` (git history: commit before the one that added this file).

## What still counts (never blocks)
`chat.usage` (messages per shop per month), `chat.ai_usage_log` (per call: tenant, model, tokens, cost), `chat.reply_traces`, `GET /api/v1/store/ai/usage` (`usedThisMonth`).

## What still limits the AI, on purpose (not product usage ceilings)
- Wallet points: the AI answers while the shop's wallet can pay for the conversation (billing, `ChatService.chargeTurn`).
- Provider-side and infrastructure protection: `GLM_MAX_RPS`, `QWEN_MAX_RPS`, provider timeouts, retries, hedging, fallback, the 45 s reply budget, burst coalescing and the per-client chat throttle (`LoginThrottle`).
- Outbound abuse/ban protection for the unofficial Messenger/Instagram senders and marketing broadcasts (`HumanPolicy` daily caps, `WA_DAILY_CAP`, `MS_DAILY_CAP`), and advertising spend guardrails (`marketing.guardrails`).
- Plan limits that are not AI messages (products, branches, staff, storage, orders and appointments per month).

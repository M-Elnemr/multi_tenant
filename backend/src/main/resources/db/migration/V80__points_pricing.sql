-- New pricing (2026-11):
--  * Shops: no subscription at all (free forever). They pay with points (1 point = 1 EGP, kept in the wallet):
--    1 point per AI conversation (each block of up to 20 messages), 0.5 point per WhatsApp confirmation / offer message.
--    Every shop gets 100 free points once.
--  * Clinics and lawyers: the first month is free, then 250 EGP a month (website hosting); yearly = 10 months.

-- 1) clinic + lawyer: 250 EGP / month
UPDATE billing.subscription_plans SET price_minor = 25000  WHERE code IN ('CLINIC_PRO', 'LAWYER_PRO');
UPDATE billing.subscription_plans SET price_minor = 250000 WHERE code IN ('CLINIC_PRO_YEARLY', 'LAWYER_PRO_YEARLY');

-- 2) shops: only the free plan stays, with everything in it (custom domain included)
UPDATE billing.subscription_plans SET is_active = FALSE WHERE tenant_type = 'STORE' AND code <> 'STORE_FREE';
UPDATE billing.subscription_plans SET name = 'Free', is_trial_plan = FALSE WHERE code = 'STORE_FREE';
UPDATE billing.subscription_plan_features SET enabled = TRUE, limit_value = NULL
WHERE plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_FREE') AND feature_key = 'custom_domain';

-- every running shop subscription becomes the free plan, active with no end
UPDATE billing.tenant_subscriptions s SET plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_FREE'),
       status = 'ACTIVE', current_period_end = now() + interval '100 years', cancel_at_period_end = FALSE, pending_plan_id = NULL, updated_at = now()
FROM core.tenants t
WHERE t.id = s.tenant_id AND t.tenant_type = 'STORE' AND s.status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED');

-- shops that were stopped only because their trial / paid period ran out come back
-- (a shop the platform suspended by hand still has a live subscription, so it is not touched here and stays suspended)
WITH revived AS (
    INSERT INTO billing.tenant_subscriptions (tenant_id, plan_id, status, current_period_end)
    SELECT t.id, (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_FREE'), 'ACTIVE', now() + interval '100 years'
    FROM core.tenants t
    WHERE t.tenant_type = 'STORE' AND t.status IN ('SUSPENDED', 'PAST_DUE')
      AND NOT EXISTS (SELECT 1 FROM billing.tenant_subscriptions s WHERE s.tenant_id = t.id AND s.status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED'))
      AND EXISTS (SELECT 1 FROM billing.tenant_subscriptions s WHERE s.tenant_id = t.id AND s.status = 'EXPIRED')
    RETURNING tenant_id
)
UPDATE core.tenants t SET status = 'ACTIVE', updated_at = now() WHERE t.id IN (SELECT tenant_id FROM revived);
UPDATE core.tenants SET status = 'ACTIVE', updated_at = now() WHERE tenant_type = 'STORE' AND status IN ('TRIAL', 'PAST_DUE');

-- 3) wallet: a GIFT kind, and 100 free points (10,000 piasters) once for every shop
ALTER TABLE billing.wallet_entries DROP CONSTRAINT IF EXISTS wallet_entries_kind_check;
ALTER TABLE billing.wallet_entries ADD CONSTRAINT wallet_entries_kind_check CHECK (kind IN ('TOPUP', 'MESSAGE', 'REFUND', 'ADJUST', 'GIFT', 'AI'));

INSERT INTO billing.wallets (tenant_id, balance_minor)
SELECT t.id, 0 FROM core.tenants t WHERE t.tenant_type = 'STORE' ON CONFLICT (tenant_id) DO NOTHING;
WITH gift AS (
    UPDATE billing.wallets w SET balance_minor = w.balance_minor + 10000
    FROM core.tenants t
    WHERE t.id = w.tenant_id AND t.tenant_type = 'STORE'
      AND NOT EXISTS (SELECT 1 FROM billing.wallet_entries e WHERE e.tenant_id = w.tenant_id AND e.ref = 'welcome-100')
    RETURNING w.tenant_id, w.balance_minor
)
INSERT INTO billing.wallet_entries (tenant_id, kind, amount_minor, balance_after_minor, ref, note)
SELECT tenant_id, 'GIFT', 10000, balance_minor, 'welcome-100', '100 free points' FROM gift;

-- 4) AI auto-reply: the owner picks the channels it answers on (all on by default, as before)
ALTER TABLE chat.settings ADD COLUMN IF NOT EXISTS channels VARCHAR(20)[] NOT NULL DEFAULT '{WHATSAPP,FACEBOOK,INSTAGRAM,WEB}';

-- 5) AI conversation metering: messages counted in the chat and how many 20-message blocks were charged
ALTER TABLE chat.conversations ADD COLUMN IF NOT EXISTS ai_msgs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat.conversations ADD COLUMN IF NOT EXISTS ai_units INTEGER NOT NULL DEFAULT 0;

-- One plan for everyone: 1000 EGP / month, the same price for clinics and shops, all features included.
-- The former Pro plans become that plan (same codes, so existing subscriptions and trials keep working); the Starter plans are retired.
UPDATE billing.subscription_plans SET name = 'Elmanassa', price_minor = 100000, sort_order = 10 WHERE code IN ('STORE_PRO', 'CLINIC_PRO');
UPDATE billing.subscription_plans SET is_active = FALSE WHERE code IN ('STORE_STARTER', 'CLINIC_STARTER');

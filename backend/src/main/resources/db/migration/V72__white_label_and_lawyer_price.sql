-- A business can run fully under its own name: no billing or domains screens for its staff and no «powered by» credit.
-- The platform turns it on per business (here: the demo law office).
ALTER TABLE core.tenants ADD COLUMN IF NOT EXISTS white_label BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE core.tenants SET white_label = TRUE WHERE slug = 'elhosseiny';

-- Lawyers: Professional is 300 EGP a month (yearly = 10 months)
UPDATE billing.subscription_plans SET price_minor = 30000 WHERE code = 'LAWYER_PRO';
UPDATE billing.subscription_plans SET price_minor = 300000 WHERE code = 'LAWYER_PRO_YEARLY';

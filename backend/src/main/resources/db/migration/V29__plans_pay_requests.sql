-- Pricing: shops Free (14 days) / Standard 500 (no custom domain) / Professional 750; clinics Free (14 days) / Professional 1000.
-- The free plans carry every Professional feature, so a trial can try the custom domain too.
UPDATE billing.subscription_plans SET name = 'Professional', price_minor = 75000, sort_order = 30 WHERE code = 'STORE_PRO';
UPDATE billing.subscription_plans SET name = 'Professional', price_minor = 100000, sort_order = 30 WHERE code = 'CLINIC_PRO';

INSERT INTO billing.subscription_plans (code, tenant_type, name, price_minor, is_trial_plan, is_active, sort_order) VALUES
 ('STORE_FREE','STORE','Free', 0, TRUE, TRUE, 10),
 ('STORE_STANDARD','STORE','Standard', 50000, FALSE, TRUE, 20),
 ('CLINIC_FREE','CLINIC','Free', 0, TRUE, TRUE, 10);

INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT n.id, f.feature_key, f.enabled, f.limit_value
FROM billing.subscription_plans n
JOIN billing.subscription_plans src ON src.code = CASE n.code WHEN 'CLINIC_FREE' THEN 'CLINIC_PRO' ELSE 'STORE_PRO' END
JOIN billing.subscription_plan_features f ON f.plan_id = src.id
WHERE n.code IN ('STORE_FREE','STORE_STANDARD','CLINIC_FREE');

UPDATE billing.subscription_plan_features SET enabled = FALSE
WHERE feature_key = 'custom_domain' AND plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_STANDARD');

-- Existing subscriptions: paying shops were on 500 -> Standard; trials -> the matching free plan.
UPDATE billing.tenant_subscriptions s SET plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_STANDARD')
WHERE s.status IN ('ACTIVE','PAST_DUE') AND s.plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_PRO');
UPDATE billing.tenant_subscriptions s SET plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_FREE')
WHERE s.status = 'TRIALING' AND s.plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_PRO');
UPDATE billing.tenant_subscriptions s SET plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'CLINIC_FREE')
WHERE s.status = 'TRIALING' AND s.plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'CLINIC_PRO');

-- Manual payment requests: online payment is not live yet, customer service contacts the owner.
CREATE TABLE billing.pay_requests (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id),
    requested_by UUID REFERENCES core.users(id),
    plan_code    VARCHAR(40) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency     VARCHAR(3) NOT NULL DEFAULT 'EGP',
    phone        VARCHAR(30) NOT NULL,
    note         TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CONTACTED','PAID','REJECTED')),
    admin_note   TEXT,
    handled_by   UUID,
    handled_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pay_requests_tenant ON billing.pay_requests (tenant_id, created_at DESC);
CREATE INDEX idx_pay_requests_status ON billing.pay_requests (status, created_at DESC);
CREATE UNIQUE INDEX uq_pay_request_open ON billing.pay_requests (tenant_id) WHERE status IN ('NEW','CONTACTED');

-- Deleting a tenant and all its data is reserved to the platform owner.
INSERT INTO core.permissions (code, name) VALUES ('platform.tenant.purge','Delete a tenant and all its data (platform)');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code = 'PLATFORM_OWNER' AND r.tenant_id IS NULL AND p.code = 'platform.tenant.purge';

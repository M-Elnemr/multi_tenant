CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.subscription_plans (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(40) NOT NULL UNIQUE,
    tenant_type VARCHAR(30) NOT NULL CHECK (tenant_type IN ('STORE','CLINIC')),
    name        VARCHAR(100) NOT NULL,
    price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
    currency    VARCHAR(3) NOT NULL DEFAULT 'EGP',
    interval    VARCHAR(10) NOT NULL DEFAULT 'MONTH' CHECK (interval IN ('MONTH','YEAR')),
    is_trial_plan BOOLEAN NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0
);

-- limit_value NULL + enabled = unlimited / on. enabled = false = feature not available.
CREATE TABLE billing.subscription_plan_features (
    plan_id     UUID NOT NULL REFERENCES billing.subscription_plans(id),
    feature_key VARCHAR(60) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    limit_value BIGINT,
    PRIMARY KEY (plan_id, feature_key)
);

CREATE TABLE billing.tenant_subscriptions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES core.tenants(id),
    plan_id              UUID NOT NULL REFERENCES billing.subscription_plans(id),
    status               VARCHAR(20) NOT NULL CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED','CANCELLED','EXPIRED')),
    current_period_start TIMESTAMPTZ NOT NULL DEFAULT now(),
    current_period_end   TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- exactly one live subscription per tenant
CREATE UNIQUE INDEX uq_one_live_subscription ON billing.tenant_subscriptions (tenant_id)
    WHERE status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED');
CREATE INDEX idx_subscriptions_tenant ON billing.tenant_subscriptions (tenant_id);

CREATE TABLE billing.subscription_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    details    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sub_events_tenant ON billing.subscription_events (tenant_id, created_at DESC);

-- Invoices are snapshots: never updated except status/paid_at.
CREATE TABLE billing.subscription_invoices (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES core.tenants(id),
    subscription_id UUID REFERENCES billing.tenant_subscriptions(id),
    invoice_number VARCHAR(40) NOT NULL UNIQUE,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','PAID','VOID')),
    currency       VARCHAR(3) NOT NULL,
    total_minor    BIGINT NOT NULL,
    target_plan_id UUID REFERENCES billing.subscription_plans(id),
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at        TIMESTAMPTZ
);
CREATE INDEX idx_invoices_tenant ON billing.subscription_invoices (tenant_id, issued_at DESC);

CREATE TABLE billing.subscription_invoice_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id  UUID NOT NULL REFERENCES billing.subscription_invoices(id),
    description VARCHAR(200) NOT NULL,
    amount_minor BIGINT NOT NULL
);

CREATE TABLE billing.subscription_payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    invoice_id          UUID NOT NULL REFERENCES billing.subscription_invoices(id),
    provider            VARCHAR(30) NOT NULL,
    provider_payment_id VARCHAR(100),
    amount_minor        BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    idempotency_key     VARCHAR(100) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE billing.usage_counters (
    tenant_id  UUID NOT NULL,
    metric     VARCHAR(60) NOT NULL,
    period     VARCHAR(10) NOT NULL DEFAULT 'ALL',  -- 'ALL' or yyyy-MM for monthly metrics
    value      BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, metric, period)
);

CREATE TABLE billing.webhook_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider           VARCHAR(30) NOT NULL,
    event_id           VARCHAR(100) NOT NULL,
    signature_verified BOOLEAN NOT NULL,
    payload_json       JSONB,
    status             VARCHAR(20) NOT NULL,
    error_message      TEXT,
    processed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, event_id)
);

-- Plans (prices are placeholders to validate with the market; editable data, never hardcoded in code) ----
INSERT INTO billing.subscription_plans (code, tenant_type, name, price_minor, is_trial_plan, sort_order) VALUES
 ('STORE_STARTER','STORE','Store Starter', 19900, FALSE, 10),
 ('STORE_PRO','STORE','Store Pro', 49900, FALSE, 20),
 ('CLINIC_STARTER','CLINIC','Clinic Starter', 29900, FALSE, 10),
 ('CLINIC_PRO','CLINIC','Clinic Pro', 79900, FALSE, 20);

INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT p.id, f.k, f.en, f.lim FROM billing.subscription_plans p JOIN (VALUES
 ('STORE_STARTER','max_products',TRUE,100::bigint),('STORE_STARTER','max_branches',TRUE,1),('STORE_STARTER','max_staff',TRUE,2),
 ('STORE_STARTER','max_monthly_orders',TRUE,300),('STORE_STARTER','custom_domain',FALSE,NULL),('STORE_STARTER','coupons',FALSE,NULL),
 ('STORE_STARTER','reviews',FALSE,NULL),('STORE_STARTER','advanced_reports',FALSE,NULL),('STORE_STARTER','inventory_multi_branch',FALSE,NULL),
 ('STORE_PRO','max_products',TRUE,NULL),('STORE_PRO','max_branches',TRUE,20),('STORE_PRO','max_staff',TRUE,15),
 ('STORE_PRO','max_monthly_orders',TRUE,NULL),('STORE_PRO','custom_domain',TRUE,NULL),('STORE_PRO','coupons',TRUE,NULL),
 ('STORE_PRO','reviews',TRUE,NULL),('STORE_PRO','advanced_reports',TRUE,NULL),('STORE_PRO','inventory_multi_branch',TRUE,NULL),
 ('CLINIC_STARTER','max_branches',TRUE,1),('CLINIC_STARTER','max_staff',TRUE,3),('CLINIC_STARTER','max_monthly_appointments',TRUE,300),
 ('CLINIC_STARTER','patient_portal',TRUE,NULL),('CLINIC_STARTER','custom_domain',FALSE,NULL),('CLINIC_STARTER','advanced_reports',FALSE,NULL),
 ('CLINIC_STARTER','whatsapp_notifications',FALSE,NULL),
 ('CLINIC_PRO','max_branches',TRUE,10),('CLINIC_PRO','max_staff',TRUE,25),('CLINIC_PRO','max_monthly_appointments',TRUE,NULL),
 ('CLINIC_PRO','patient_portal',TRUE,NULL),('CLINIC_PRO','custom_domain',TRUE,NULL),('CLINIC_PRO','advanced_reports',TRUE,NULL),
 ('CLINIC_PRO','whatsapp_notifications',TRUE,NULL)
) AS f(code,k,en,lim) ON f.code = p.code;

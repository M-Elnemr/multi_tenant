-- Automatic WhatsApp order confirmation, paid from a prepaid credit (1 EGP per template message) ----------------
ALTER TABLE chat.channels ADD COLUMN waba_id VARCHAR(40);   -- WhatsApp Business Account id (needed to register message templates)

CREATE TABLE chat.wa_settings (
    tenant_id           UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    auto_confirm_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_at          TIMESTAMPTZ,                       -- only orders placed after this are contacted
    confirm_after_hours INTEGER NOT NULL DEFAULT 22 CHECK (confirm_after_hours BETWEEN 1 AND 72),
    reminder_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_after_hours INTEGER NOT NULL DEFAULT 24 CHECK (reminder_after_hours BETWEEN 1 AND 72),
    brand_name          VARCHAR(40),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat.wa_templates (
    tenant_id        UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name             VARCHAR(60) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejected_reason  VARCHAR(300),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);

-- When a number last wrote to the shop: inside 24 hours a normal (free) message is allowed, outside it only a paid template.
CREATE TABLE chat.wa_windows (
    tenant_id        UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    number           VARCHAR(20) NOT NULL,
    last_inbound_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, number)
);

CREATE TABLE chat.wa_outbound (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    order_id    UUID NOT NULL REFERENCES commerce.orders(id) ON DELETE CASCADE,
    kind        VARCHAR(10) NOT NULL CHECK (kind IN ('CONFIRM', 'REMINDER')),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('SENT', 'FREE_TEXT', 'FAILED', 'WAITING_CREDIT')),
    attempts    INTEGER NOT NULL DEFAULT 0,
    cost_minor  INTEGER NOT NULL DEFAULT 0,
    wamid       VARCHAR(120),
    error       VARCHAR(300),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    UNIQUE (order_id, kind)                                -- one confirmation and one reminder per order, ever
);
CREATE INDEX wa_outbound_tenant_idx ON chat.wa_outbound (tenant_id, created_at DESC);

CREATE TABLE billing.wallets (
    tenant_id     UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0)   -- the balance can never go below zero
);

CREATE TABLE billing.wallet_entries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    kind                VARCHAR(10) NOT NULL CHECK (kind IN ('TOPUP', 'MESSAGE', 'REFUND', 'ADJUST')),
    amount_minor        BIGINT NOT NULL,
    balance_after_minor BIGINT NOT NULL,
    ref                 VARCHAR(100),
    note                VARCHAR(300),
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX wallet_entries_tenant_idx ON billing.wallet_entries (tenant_id, created_at DESC);

CREATE TABLE billing.topup_requests (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    phone        VARCHAR(30),
    note         VARCHAR(300),
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    admin_note   VARCHAR(300),
    handled_by   UUID,
    handled_at   TIMESTAMPTZ,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX topup_requests_status_idx ON billing.topup_requests (status, created_at DESC);

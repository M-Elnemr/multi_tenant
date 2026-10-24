-- Wave 2: loyalty points, customer segments and broadcasts, server-side Meta events.

CREATE TABLE commerce.loyalty_settings (
    tenant_id       UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    earn_percent    INTEGER NOT NULL DEFAULT 5 CHECK (earn_percent BETWEEN 1 AND 50),   -- % of the paid goods value returned as points; 1 point = 1 currency unit
    min_redeem      INTEGER NOT NULL DEFAULT 20 CHECK (min_redeem >= 1),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Points belong to a phone number (guests have no account); only the signed-in owner of that verified phone can spend them.
CREATE TABLE commerce.loyalty_accounts (
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    phone           VARCHAR(30) NOT NULL,
    points          INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    lifetime_points INTEGER NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, phone)
);

CREATE TABLE commerce.loyalty_ledger (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    phone       VARCHAR(30) NOT NULL,
    delta       INTEGER NOT NULL,
    reason      VARCHAR(10) NOT NULL CHECK (reason IN ('EARN', 'REDEEM', 'REVERSE')),
    order_id    UUID,
    coupon_code VARCHAR(40),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loyalty_ledger_phone ON commerce.loyalty_ledger (tenant_id, phone, id DESC);
CREATE UNIQUE INDEX uq_loyalty_earn_order ON commerce.loyalty_ledger (order_id, reason) WHERE order_id IS NOT NULL;

-- WhatsApp broadcasts to a segment: only customers who agreed to WhatsApp contact and whose 24 h window is open (free). Never a paid message.
CREATE TABLE commerce.broadcasts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    segment       VARCHAR(12) NOT NULL,
    message       VARCHAR(800) NOT NULL,
    audience      INTEGER NOT NULL DEFAULT 0,
    sent_count    INTEGER NOT NULL DEFAULT 0,
    skipped_window INTEGER NOT NULL DEFAULT 0,
    skipped_consent INTEGER NOT NULL DEFAULT 0,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_broadcasts_tenant ON commerce.broadcasts (tenant_id, created_at DESC);

-- Server-side purchase events (Meta Conversions API). The token is stored encrypted.
CREATE TABLE commerce.tracking_settings (
    tenant_id       UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    capi_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    capi_token_enc  TEXT,
    last_status     VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO core.permissions (code, name) VALUES ('growth.manage', 'Loyalty, segments, broadcasts, product feeds and tracking');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code = 'growth.manage'
ON CONFLICT DO NOTHING;

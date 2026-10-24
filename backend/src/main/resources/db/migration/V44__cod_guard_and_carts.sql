-- Wave 1: COD risk guard and incomplete (abandoned) carts.

ALTER TABLE commerce.orders
    ADD COLUMN risk_score         INTEGER,
    ADD COLUMN risk_level         VARCHAR(8) CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    ADD COLUMN risk_reasons       VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN needs_confirmation BOOLEAN NOT NULL DEFAULT FALSE;   -- true: the owner must confirm (call) before the order can be prepared or shipped
CREATE INDEX idx_orders_risk ON commerce.orders (tenant_id, risk_level) WHERE risk_level = 'HIGH';

-- Per shop. Scoring is always on (it only labels orders); holding is a choice.
CREATE TABLE commerce.cod_settings (
    tenant_id          UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    hold_high_risk     BOOLEAN NOT NULL DEFAULT FALSE,
    hold_medium_risk   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cart recovery. Never sends a paid WhatsApp message unless the shop switched allow_paid on (and then only from its own prepaid credit).
CREATE TABLE commerce.cart_settings (
    tenant_id          UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    recovery_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    allow_paid         BOOLEAN NOT NULL DEFAULT FALSE,
    remind_after_hours INTEGER NOT NULL DEFAULT 24 CHECK (remind_after_hours BETWEEN 1 AND 168),
    coupon_code        VARCHAR(40),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.abandoned_carts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    client_key         VARCHAR(80) NOT NULL,
    user_id            UUID,
    name               VARCHAR(150),
    phone              VARCHAR(30) NOT NULL,
    items              JSONB NOT NULL DEFAULT '[]',
    item_count         INTEGER NOT NULL DEFAULT 0,
    total_minor        BIGINT NOT NULL DEFAULT 0,
    currency           VARCHAR(3) NOT NULL DEFAULT 'EGP',
    status             VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RECOVERED', 'DISMISSED', 'EXPIRED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    contacted_at       TIMESTAMPTZ,
    reminder_status    VARCHAR(16),   -- SENT_FREE, SENT_PAID, NOT_SENT_WINDOW, NO_CREDIT, NOT_APPROVED, FAILED
    reminder_sent_at   TIMESTAMPTZ,
    recovered_order_id UUID,
    recovered_at       TIMESTAMPTZ,
    UNIQUE (tenant_id, client_key)
);
CREATE INDEX idx_abandoned_open ON commerce.abandoned_carts (tenant_id, created_at) WHERE status = 'OPEN';
CREATE INDEX idx_abandoned_phone ON commerce.abandoned_carts (tenant_id, phone) WHERE status = 'OPEN';

INSERT INTO core.permissions (code, name) VALUES ('cart.read', 'View incomplete carts'), ('cart.manage', 'Contact customers about incomplete carts and set recovery');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN', 'STORE_MANAGER') AND r.tenant_id IS NULL AND p.code IN ('cart.read', 'cart.manage')
ON CONFLICT DO NOTHING;

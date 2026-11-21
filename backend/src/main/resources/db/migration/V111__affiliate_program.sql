-- Affiliate (marketer) program, phase 1: each shop runs its own program. The platform never holds or moves the money:
-- it records who brought an order, the commission rule that applied (copied onto the commission), the state of each commission
-- and the payments the shop reports as made. Every state change is an append-only ledger line.
CREATE SCHEMA IF NOT EXISTS affiliate;

CREATE TABLE affiliate.programs (
    tenant_id       UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled         BOOLEAN NOT NULL DEFAULT false,
    percent         NUMERIC(5,2) NOT NULL DEFAULT 5 CHECK (percent >= 0 AND percent <= 50),
    window_days     INT NOT NULL DEFAULT 30 CHECK (window_days BETWEEN 1 AND 365),
    hold_days       INT NOT NULL DEFAULT 14 CHECK (hold_days BETWEEN 0 AND 90),
    min_payout_minor BIGINT NOT NULL DEFAULT 0 CHECK (min_payout_minor >= 0),
    terms           TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE affiliate.partners (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name        VARCHAR(160) NOT NULL,
    phone       VARCHAR(20),
    code        VARCHAR(24) NOT NULL,
    percent     NUMERIC(5,2) CHECK (percent IS NULL OR (percent >= 0 AND percent <= 50)),   -- NULL = the program's rate
    status      VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE affiliate.visits (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id  UUID NOT NULL REFERENCES affiliate.partners(id) ON DELETE CASCADE,
    visited_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aff_visits ON affiliate.visits (tenant_id, partner_id, visited_at);

CREATE TABLE affiliate.commissions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id    UUID NOT NULL REFERENCES affiliate.partners(id),
    order_id      UUID NOT NULL,
    order_number  VARCHAR(40) NOT NULL,
    base_minor    BIGINT NOT NULL CHECK (base_minor >= 0),          -- products after discounts, shipping excluded
    percent       NUMERIC(5,2) NOT NULL,                            -- the rate that applied when the order was placed
    amount_minor  BIGINT NOT NULL CHECK (amount_minor >= 0),
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REVERSED','PAID')),
    hold_until    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id)                                               -- an order earns at most one commission
);
CREATE INDEX idx_aff_comm ON affiliate.commissions (tenant_id, partner_id, status);

CREATE TABLE affiliate.ledger (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    commission_id UUID NOT NULL REFERENCES affiliate.commissions(id),
    kind          VARCHAR(10) NOT NULL CHECK (kind IN ('ACCRUE','APPROVE','REVERSE','PAYOUT')),
    amount_minor  BIGINT NOT NULL,
    note          VARCHAR(300),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aff_ledger ON affiliate.ledger (tenant_id, commission_id);

ALTER TABLE commerce.orders ADD COLUMN affiliate_code VARCHAR(24);

INSERT INTO core.permissions (code, name) VALUES ('affiliate.manage', 'Run the shop''s affiliate (marketer) program');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code = 'affiliate.manage'
ON CONFLICT DO NOTHING;

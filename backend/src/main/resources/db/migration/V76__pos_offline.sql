-- The point of sale works with no internet: a registered terminal (a browser on the counter) keeps the catalog, cashiers and its own sales locally and sends
-- every action later, in order, each with an id made on the device so a resend is never applied twice. Shifts, cash in/out, the drawer handed over at the
-- end of a shift and stock counts are recorded the same way. A sale that was already paid is never refused when it arrives; what does not add up (stock
-- that ran out, a price changed meanwhile, a return of more than was left) becomes a sync issue for the manager.

CREATE TABLE commerce.pos_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    branch_id UUID NOT NULL REFERENCES commerce.branches(id),
    name VARCHAR(80) NOT NULL,
    code VARCHAR(8) NOT NULL,                       -- short, part of every invoice number made on this device (POS-A1-000123)
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    receipt_size VARCHAR(8) CHECK (receipt_size IN ('58', '80', 'A4')),   -- null: the shop's default
    last_seen_at TIMESTAMPTZ, last_seq BIGINT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

-- A cashier's PIN, checked on the terminal with no internet (PBKDF2-SHA256, the browser's own WebCrypto) and never sent back in clear.
CREATE TABLE commerce.pos_pins (
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    user_id UUID NOT NULL REFERENCES core.users(id),
    salt VARCHAR(64) NOT NULL, iterations INT NOT NULL, pin_hash VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE commerce.pos_shifts (
    id UUID PRIMARY KEY,                            -- made on the device
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    branch_id UUID NOT NULL, device_id UUID NOT NULL REFERENCES commerce.pos_devices(id),
    cashier_id UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL, opening_float_minor BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMPTZ, counted_cash_minor BIGINT, expected_cash_minor BIGINT, difference_minor BIGINT, device_expected_minor BIGINT,
    denominations JSONB, note TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    handover_kind VARCHAR(14) CHECK (handover_kind IN ('NEXT_CASHIER', 'SAFE')), handed_to UUID,
    currency VARCHAR(3) NOT NULL DEFAULT 'EGP',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pos_shifts_tenant_idx ON commerce.pos_shifts (tenant_id, opened_at DESC);

CREATE TABLE commerce.pos_cash_moves (
    id UUID PRIMARY KEY,                            -- made on the device
    tenant_id UUID NOT NULL, shift_id UUID NOT NULL REFERENCES commerce.pos_shifts(id),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('PAY_IN', 'PAY_OUT', 'DROP')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0), reason VARCHAR(200),
    at TIMESTAMPTZ NOT NULL, user_id UUID NOT NULL
);
CREATE INDEX pos_cash_moves_shift_idx ON commerce.pos_cash_moves (shift_id);

-- Every action a terminal sent: the id it made is unique, so the same action sent twice is answered from here and never applied twice.
CREATE TABLE commerce.pos_ops (
    op_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL, device_id UUID NOT NULL, seq BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL, client_at TIMESTAMPTZ, received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(10) NOT NULL CHECK (status IN ('APPLIED', 'FLAGGED', 'FAILED')),
    result JSONB, error TEXT
);
CREATE INDEX pos_ops_device_idx ON commerce.pos_ops (device_id, seq);

CREATE TABLE commerce.pos_sync_issues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, op_id UUID, device_id UUID,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('NEGATIVE_STOCK', 'PRICE_CHANGED', 'PRODUCT_INACTIVE', 'RETURN_EXCEEDS', 'SHIFT_MISMATCH', 'CLOCK', 'FAILED')),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ, resolved_by UUID
);
CREATE INDEX pos_sync_issues_open_idx ON commerce.pos_sync_issues (tenant_id, created_at DESC) WHERE resolved_at IS NULL;

CREATE TABLE commerce.stock_counts (
    id UUID PRIMARY KEY,                            -- made on the device (or the server for one started on the dashboard)
    tenant_id UUID NOT NULL, branch_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'COUNTING' CHECK (status IN ('COUNTING', 'REVIEW', 'APPLIED', 'CANCELLED')),
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_by UUID, applied_at TIMESTAMPTZ
);
CREATE TABLE commerce.stock_count_lines (
    count_id UUID NOT NULL REFERENCES commerce.stock_counts(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL,
    expected_at_count INT NOT NULL,                 -- what the terminal believed was on the shelf when it was counted: the change applied is counted minus this
    counted INT NOT NULL CHECK (counted >= 0),
    counted_at TIMESTAMPTZ NOT NULL, counted_by UUID,
    PRIMARY KEY (count_id, variant_id)
);

ALTER TABLE commerce.orders
    ADD COLUMN pos_device_id UUID, ADD COLUMN pos_shift_id UUID, ADD COLUMN cashier_id UUID,
    ADD COLUMN client_created_at TIMESTAMPTZ, ADD COLUMN offline BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN tendered_minor BIGINT, ADD COLUMN change_minor BIGINT;
CREATE INDEX orders_pos_shift_idx ON commerce.orders (pos_shift_id) WHERE pos_shift_id IS NOT NULL;

ALTER TABLE commerce.order_returns ADD COLUMN op_id UUID UNIQUE, ADD COLUMN pos_shift_id UUID, ADD COLUMN refund_method VARCHAR(30);

ALTER TABLE commerce.store_profiles
    ADD COLUMN receipt_size VARCHAR(8) NOT NULL DEFAULT '80' CHECK (receipt_size IN ('58', '80', 'A4')),
    ADD COLUMN receipt_footer VARCHAR(300),
    ADD COLUMN pos_max_discount_pct INT NOT NULL DEFAULT 10 CHECK (pos_max_discount_pct BETWEEN 0 AND 100);

-- A count applied moves stock with its own type, so the movements list tells a count from a hand adjustment.
ALTER TABLE commerce.inventory_movements DROP CONSTRAINT IF EXISTS inventory_movements_type_check;
ALTER TABLE commerce.inventory_movements ADD CONSTRAINT inventory_movements_type_check
    CHECK (type IN ('PURCHASE','SALE','RESERVATION','RELEASE','ADJUSTMENT','RETURN','TRANSFER_IN','TRANSFER_OUT','DAMAGE','COUNT'));

INSERT INTO core.permissions (code, name) VALUES
    ('pos.manage', 'Point of sale terminals, cashier PINs, shifts and sync issues'),
    ('pos.cash', 'Cash in and out of the drawer')
ON CONFLICT (code) DO NOTHING;
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN', 'STORE_MANAGER') AND r.tenant_id IS NULL AND p.code IN ('pos.manage', 'pos.cash')
ON CONFLICT DO NOTHING;

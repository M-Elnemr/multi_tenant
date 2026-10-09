-- Egyptian delivery: price/ETA by governorate zone, COD controls, confirmation, tracking, returns, phone history.
ALTER TABLE commerce.shipping_methods DROP CONSTRAINT IF EXISTS shipping_methods_type_check;
ALTER TABLE commerce.shipping_methods ADD CONSTRAINT shipping_methods_type_check CHECK (type IN ('PICKUP','FIXED','FREE_ABOVE','ZONES'));

CREATE TABLE commerce.shipping_zones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(120) NOT NULL,
    governorate_codes TEXT[] NOT NULL DEFAULT '{}',
    fee_minor BIGINT NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    free_above_minor BIGINT,
    cod_fee_minor BIGINT NOT NULL DEFAULT 0 CHECK (cod_fee_minor >= 0),
    eta_min_days INT NOT NULL DEFAULT 1,
    eta_max_days INT NOT NULL DEFAULT 3,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipping_zones_tenant ON commerce.shipping_zones (tenant_id);

ALTER TABLE commerce.orders
    ADD COLUMN governorate_code VARCHAR(10) NOT NULL DEFAULT '',
    ADD COLUMN area VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN landmark VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN phone2 VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN cod_fee_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN eta_min_days INT,
    ADD COLUMN eta_max_days INT,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN confirmed_by UUID,
    ADD COLUMN courier_name VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN tracking_number VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN tracking_url VARCHAR(400) NOT NULL DEFAULT '',
    ADD COLUMN shipped_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ;
CREATE INDEX idx_orders_unconfirmed ON commerce.orders (tenant_id, created_at DESC) WHERE status = 'REQUESTED' AND confirmed_at IS NULL;
CREATE INDEX idx_orders_phone ON commerce.orders (tenant_id, customer_phone_snapshot);

-- Per-shop history of a phone number: who often refuses/returns, and numbers the owner chose to block.
CREATE TABLE commerce.phone_flags (
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    phone VARCHAR(30) NOT NULL,
    delivered_count INT NOT NULL DEFAULT 0,
    returned_count INT NOT NULL DEFAULT 0,
    cancelled_count INT NOT NULL DEFAULT 0,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(300) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, phone)
);

CREATE TABLE commerce.return_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    order_id UUID NOT NULL REFERENCES commerce.orders(id),
    reason VARCHAR(40) NOT NULL DEFAULT 'OTHER',
    details VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(12) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','REJECTED','RECEIVED')),
    owner_note VARCHAR(500) NOT NULL DEFAULT '',
    decided_by UUID,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_return_open ON commerce.return_requests (order_id) WHERE status IN ('REQUESTED','APPROVED');
CREATE INDEX idx_return_tenant ON commerce.return_requests (tenant_id, created_at DESC);

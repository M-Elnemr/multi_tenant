-- Open Store: one marketplace (open.<root-domain>) that lists the products of every live shop ------------------
-- A shop is listed automatically; the platform admin can hide one (open_listed) and set its commission rate.
-- Orders placed in the marketplace are ordinary shop orders (same flow as the shop's own site); the commission is only recorded.

ALTER TABLE commerce.store_profiles
    ADD COLUMN open_listed BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN open_commission_bps INT NOT NULL DEFAULT 1000 CHECK (open_commission_bps BETWEEN 0 AND 10000);   -- 1000 = 10%

ALTER TABLE commerce.orders
    ADD COLUMN open_group_id UUID,
    ADD COLUMN commission_bps INT,
    ADD COLUMN commission_minor BIGINT;

CREATE INDEX idx_orders_open_group ON commerce.orders (open_group_id) WHERE open_group_id IS NOT NULL;

CREATE SCHEMA open;

-- A marketplace shopper: phone + password, no OTP. Separate from any shop's own client accounts.
CREATE TABLE open.accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone         VARCHAR(20)  NOT NULL UNIQUE,
    name          VARCHAR(150) NOT NULL DEFAULT '',
    password_hash VARCHAR(200) NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Opaque bearer tokens, stored hashed.
CREATE TABLE open.sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES open.accounts(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_open_sessions_account ON open.sessions (account_id);

-- One cart checked out in the marketplace = one group of orders (one per shop).
CREATE TABLE open.order_groups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID REFERENCES open.accounts(id) ON DELETE SET NULL,
    phone           VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_open_groups_account ON open.order_groups (account_id, created_at DESC);

-- Cross-shop listing sorts/filters on these.
CREATE INDEX IF NOT EXISTS idx_products_active_created ON commerce.products (created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_products_active_sold ON commerce.products (sold_count DESC) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_reviews_product_approved ON commerce.reviews (product_id) WHERE status = 'APPROVED';

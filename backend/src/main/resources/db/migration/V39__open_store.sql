-- Open Store: one marketplace (open.<root-domain>) that lists the products of every live shop ------------------
-- Free for every shop, and an order placed there is an ordinary order in the shop it was bought from (orders.source = 'OPEN').
-- Shoppers are the same Google-signed-in client accounts the shops use (commerce.client_accounts); this only adds the sessions
-- of the marketplace apps and the admin's switch to hide a shop.

ALTER TABLE commerce.store_profiles ADD COLUMN open_listed BOOLEAN NOT NULL DEFAULT TRUE;

CREATE SCHEMA open;

-- Opaque bearer tokens of the marketplace website/app, stored hashed.
CREATE TABLE open.sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES commerce.client_accounts(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_open_sessions_account ON open.sessions (account_id);

-- the account's orders across shops
CREATE INDEX idx_orders_user_open ON commerce.orders (user_id, created_at DESC) WHERE source = 'OPEN';

-- Cross-shop listing sorts/filters on these.
CREATE INDEX IF NOT EXISTS idx_products_active_created ON commerce.products (created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_products_active_sold ON commerce.products (sold_count DESC) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_reviews_product_approved ON commerce.reviews (product_id) WHERE status = 'APPROVED';

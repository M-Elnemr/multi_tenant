-- Automatic offers (sales, flash sales, buy X get Y, bundles, quantity tiers, spend-and-save, free shipping, gifts, first order).
-- Coupons stay in commerce.coupons; the offers screen shows them as one more offer type.
CREATE TABLE commerce.offers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(120) NOT NULL,
    type VARCHAR(24) NOT NULL CHECK (type IN ('PERCENT_OFF','FIXED_OFF','FLASH_SALE','BUY_X_GET_Y','BUNDLE_PRICE','TIERED_QTY','ORDER_THRESHOLD','FREE_SHIPPING','GIFT_WITH_PURCHASE','FIRST_ORDER')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,           -- false = paused (or a draft)
    is_draft BOOLEAN NOT NULL DEFAULT FALSE,
    starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ,
    priority INT NOT NULL DEFAULT 0,
    stackable BOOLEAN NOT NULL DEFAULT FALSE,           -- may add up with other order offers
    applies_to VARCHAR(10) NOT NULL DEFAULT 'ALL' CHECK (applies_to IN ('ALL','PRODUCTS','CATEGORIES')),
    badge_text VARCHAR(40) NOT NULL DEFAULT '',
    description VARCHAR(500) NOT NULL DEFAULT '',
    banner_image VARCHAR(500),
    show_banner BOOLEAN NOT NULL DEFAULT FALSE,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_uses INT,
    usage_count INT NOT NULL DEFAULT 0,
    discount_given_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_offers_tenant ON commerce.offers (tenant_id, is_active, starts_at, ends_at);

CREATE TABLE commerce.offer_targets (
    offer_id UUID NOT NULL REFERENCES commerce.offers(id) ON DELETE CASCADE,
    target_type VARCHAR(10) NOT NULL CHECK (target_type IN ('PRODUCT','CATEGORY')),
    target_id VARCHAR(40) NOT NULL,                    -- product uuid or taxonomy id
    PRIMARY KEY (offer_id, target_type, target_id)
);

ALTER TABLE commerce.orders ADD COLUMN applied_offers JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE commerce.coupons ADD COLUMN name VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE commerce.coupons ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_orders_tenant_created ON commerce.orders (tenant_id, created_at DESC);

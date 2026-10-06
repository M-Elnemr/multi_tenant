CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE SCHEMA IF NOT EXISTS commerce;

-- extra permissions for the store module
INSERT INTO core.permissions (code, name) VALUES
 ('category.manage','Manage categories'),('branch.manage','Manage branches'),('coupon.manage','Manage coupons'),
 ('review.moderate','Moderate reviews'),('shipping.manage','Manage shipping & payment methods');
CREATE OR REPLACE FUNCTION pg_temp.grant_perms(role_code TEXT, perm_codes TEXT[]) RETURNS VOID AS $$
    INSERT INTO core.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM core.roles r, core.permissions p
    WHERE r.code = role_code AND r.tenant_id IS NULL AND p.code = ANY(perm_codes) ON CONFLICT DO NOTHING;
$$ LANGUAGE sql;
SELECT pg_temp.grant_perms('STORE_OWNER', ARRAY['category.manage','branch.manage','coupon.manage','review.moderate','shipping.manage']);
SELECT pg_temp.grant_perms('STORE_ADMIN', ARRAY['category.manage','branch.manage','coupon.manage','review.moderate','shipping.manage']);
SELECT pg_temp.grant_perms('STORE_MANAGER', ARRAY['category.manage','review.moderate']);
SELECT pg_temp.grant_perms('CUSTOMER_SUPPORT', ARRAY['review.moderate']);

CREATE TABLE commerce.store_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES core.tenants(id),
    store_name VARCHAR(200) NOT NULL,
    short_description TEXT, about TEXT,
    support_phone VARCHAR(30), support_email VARCHAR(254), address_text TEXT,
    shipping_policy TEXT, return_policy TEXT, privacy_policy TEXT, terms_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.branches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(120) NOT NULL, code VARCHAR(30) NOT NULL, phone VARCHAR(30),
    address_line1 VARCHAR(200) NOT NULL DEFAULT '', address_line2 VARCHAR(200),
    city VARCHAR(100) NOT NULL DEFAULT '', state VARCHAR(100), country VARCHAR(2) NOT NULL DEFAULT 'EG',
    district VARCHAR(100), postal_code VARCHAR(20), latitude NUMERIC(9,6), longitude NUMERIC(9,6),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE commerce.categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    parent_id UUID REFERENCES commerce.categories(id),
    name VARCHAR(150) NOT NULL, slug VARCHAR(160) NOT NULL, description TEXT,
    image_file_id UUID, sort_order INT NOT NULL DEFAULT 0, is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

CREATE TABLE commerce.products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    category_id UUID REFERENCES commerce.categories(id),
    name VARCHAR(250) NOT NULL, slug VARCHAR(270) NOT NULL,
    description TEXT NOT NULL DEFAULT '', short_description TEXT, brand VARCHAR(100),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    has_variants BOOLEAN NOT NULL DEFAULT FALSE,
    currency VARCHAR(3) NOT NULL DEFAULT 'EGP',
    seo_title VARCHAR(250), seo_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);
CREATE INDEX idx_products_tenant_status ON commerce.products (tenant_id, status);
CREATE INDEX idx_products_name_trgm ON commerce.products USING gin (name gin_trgm_ops);

CREATE TABLE commerce.product_options (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL, sort_order INT NOT NULL DEFAULT 0, UNIQUE (product_id, name)
);
CREATE TABLE commerce.product_option_values (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    option_id UUID NOT NULL REFERENCES commerce.product_options(id) ON DELETE CASCADE,
    value VARCHAR(80) NOT NULL, sort_order INT NOT NULL DEFAULT 0, UNIQUE (option_id, value)
);

CREATE TABLE commerce.product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    product_id UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    sku VARCHAR(80) NOT NULL, barcode VARCHAR(80),
    price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
    compare_at_price_minor BIGINT, cost_price_minor BIGINT,
    currency VARCHAR(3) NOT NULL DEFAULT 'EGP', weight_grams INT,
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    combo_key VARCHAR(500) NOT NULL,   -- canonical "Option=Value|Option=Value": one exact combination per product
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, sku), UNIQUE (product_id, combo_key)
);
CREATE INDEX idx_variants_product ON commerce.product_variants (tenant_id, product_id);

CREATE TABLE commerce.variant_option_values (
    variant_id UUID NOT NULL REFERENCES commerce.product_variants(id) ON DELETE CASCADE,
    option_value_id UUID NOT NULL REFERENCES commerce.product_option_values(id),
    PRIMARY KEY (variant_id, option_value_id)
);

CREATE TABLE commerce.product_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    product_id UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    variant_id UUID, file_id UUID, url VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0, alt_text VARCHAR(250)
);

CREATE TABLE commerce.inventory_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    branch_id UUID NOT NULL REFERENCES commerce.branches(id),
    variant_id UUID NOT NULL REFERENCES commerce.product_variants(id) ON DELETE CASCADE,
    quantity_on_hand INT NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
    quantity_reserved INT NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    low_stock_threshold INT NOT NULL DEFAULT 3,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, variant_id),
    CHECK (quantity_reserved <= quantity_on_hand)   -- can never oversell, even if application code is wrong
);
CREATE INDEX idx_inventory_tenant_variant ON commerce.inventory_items (tenant_id, variant_id);

CREATE TABLE commerce.inventory_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, branch_id UUID NOT NULL, variant_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('PURCHASE','SALE','RESERVATION','RELEASE','ADJUSTMENT','RETURN','TRANSFER_IN','TRANSFER_OUT','DAMAGE')),
    quantity_delta INT NOT NULL,
    reference_type VARCHAR(30), reference_id UUID, reason TEXT, created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_movements_tenant ON commerce.inventory_movements (tenant_id, variant_id, created_at DESC);

CREATE TABLE commerce.customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    user_id UUID REFERENCES core.users(id),
    customer_number VARCHAR(20) NOT NULL, notes TEXT, marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_number), UNIQUE (tenant_id, user_id)
);

CREATE TABLE commerce.addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES core.users(id),
    title VARCHAR(80), recipient_name VARCHAR(150) NOT NULL, phone VARCHAR(30) NOT NULL,
    address_line1 VARCHAR(250) NOT NULL, address_line2 VARCHAR(250),
    city VARCHAR(100) NOT NULL, state VARCHAR(100), district VARCHAR(100), country VARCHAR(2) NOT NULL DEFAULT 'EG',
    postal_code VARCHAR(20), latitude NUMERIC(9,6), longitude NUMERIC(9,6),
    is_default_shipping BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_addresses_user ON commerce.addresses (user_id);

CREATE TABLE commerce.payment_method_settings (
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    method VARCHAR(30) NOT NULL CHECK (method IN ('CARD','CASH_ON_DELIVERY','WALLET','BANK_TRANSFER')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, method)
);

CREATE TABLE commerce.shipping_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('PICKUP','FIXED','FREE_ABOVE')),
    name VARCHAR(120) NOT NULL, fee_minor BIGINT NOT NULL DEFAULT 0, free_above_minor BIGINT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE, sort_order INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_shipping_tenant ON commerce.shipping_methods (tenant_id);

CREATE TABLE commerce.coupons (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    code VARCHAR(40) NOT NULL, discount_type VARCHAR(10) NOT NULL CHECK (discount_type IN ('PERCENT','FIXED')),
    value BIGINT NOT NULL CHECK (value > 0),   -- percent (1-100) or minor units
    min_order_minor BIGINT NOT NULL DEFAULT 0, starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ,
    max_redemptions INT, per_customer_limit INT, is_active BOOLEAN NOT NULL DEFAULT TRUE,
    redemptions INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE TABLE commerce.coupon_redemptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, coupon_id UUID NOT NULL REFERENCES commerce.coupons(id),
    user_id UUID NOT NULL, order_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.order_counters (
    tenant_id UUID NOT NULL, day DATE NOT NULL, seq INT NOT NULL DEFAULT 0, PRIMARY KEY (tenant_id, day)
);

CREATE TABLE commerce.orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    order_number VARCHAR(40) NOT NULL,
    customer_id UUID REFERENCES commerce.customers(id), user_id UUID REFERENCES core.users(id),
    status VARCHAR(20) NOT NULL, payment_status VARCHAR(20) NOT NULL, fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'UNFULFILLED',
    payment_method VARCHAR(30) NOT NULL, shipping_method_id UUID, coupon_code VARCHAR(40),
    currency VARCHAR(3) NOT NULL,
    subtotal_minor BIGINT NOT NULL, discount_minor BIGINT NOT NULL DEFAULT 0, shipping_minor BIGINT NOT NULL DEFAULT 0,
    tax_minor BIGINT NOT NULL DEFAULT 0, total_minor BIGINT NOT NULL,
    customer_name_snapshot VARCHAR(150) NOT NULL, customer_phone_snapshot VARCHAR(30) NOT NULL,
    shipping_address_snapshot JSONB, billing_address_snapshot JSONB, notes TEXT,
    idempotency_key VARCHAR(100), reservation_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, order_number), UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_orders_tenant_created ON commerce.orders (tenant_id, created_at DESC);
CREATE INDEX idx_orders_tenant_status ON commerce.orders (tenant_id, status);
CREATE INDEX idx_orders_tenant_user ON commerce.orders (tenant_id, user_id);

CREATE TABLE commerce.order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES commerce.orders(id),
    tenant_id UUID NOT NULL,
    product_id UUID, variant_id UUID, branch_id UUID,
    sku_snapshot VARCHAR(80) NOT NULL, product_name_snapshot VARCHAR(250) NOT NULL, variant_name_snapshot VARCHAR(250),
    unit_price_minor BIGINT NOT NULL, quantity INT NOT NULL CHECK (quantity > 0),
    discount_minor BIGINT NOT NULL DEFAULT 0, line_total_minor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_items_order ON commerce.order_items (order_id);

CREATE TABLE commerce.order_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES commerce.orders(id), tenant_id UUID NOT NULL,
    from_status VARCHAR(20), new_status VARCHAR(20) NOT NULL, changed_by UUID, reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.order_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES commerce.orders(id), tenant_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL DEFAULT 'PAYMENT' CHECK (kind IN ('PAYMENT','REFUND')),
    original_payment_id UUID REFERENCES commerce.order_payments(id),   -- a refund always references its payment
    provider VARCHAR(30), method VARCHAR(30) NOT NULL, provider_payment_id VARCHAR(100),
    amount_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE, paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.wishlist_items (
    tenant_id UUID NOT NULL, user_id UUID NOT NULL, product_id UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (tenant_id, user_id, product_id)
);

CREATE TABLE commerce.reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, product_id UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES commerce.customers(id),
    rating INT NOT NULL CHECK (rating BETWEEN 1 AND 5), review_text TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, product_id, customer_id)
);

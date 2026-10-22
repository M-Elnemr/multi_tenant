-- Sales channels (marketplaces + store platforms), shipping carriers, social/ads marketing ------------------------------------------

CREATE SCHEMA integrations;
CREATE SCHEMA marketing;

-- One row per connected external account: a marketplace seller account, a store on another platform, a carrier account, or a Meta ad account.
CREATE TABLE integrations.accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    provider        VARCHAR(30) NOT NULL,
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('MARKETPLACE', 'STORE_PLATFORM', 'CARRIER', 'ADS')),
    country         VARCHAR(2) NOT NULL DEFAULT 'EG',
    label           VARCHAR(160) NOT NULL DEFAULT '',
    external_id     VARCHAR(160) NOT NULL DEFAULT '',
    credentials_enc TEXT NOT NULL,                       -- AES-GCM encrypted JSON, never returned by the API
    settings        JSONB NOT NULL DEFAULT '{}',
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ERROR', 'PAUSED')),
    last_sync_at    TIMESTAMPTZ,
    last_error      VARCHAR(500),
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, provider, country, external_id)
);
CREATE INDEX idx_integration_accounts_tenant ON integrations.accounts (tenant_id, kind);

-- A product on an external channel, linked (or not yet) to an elmanassa variant. Price and quantity are per channel.
CREATE TABLE integrations.listings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    account_id          UUID NOT NULL REFERENCES integrations.accounts(id) ON DELETE CASCADE,
    variant_id          UUID REFERENCES commerce.product_variants(id) ON DELETE SET NULL,
    product_id          UUID REFERENCES commerce.products(id) ON DELETE SET NULL,
    external_product_id VARCHAR(160) NOT NULL,
    external_variant_id VARCHAR(160) NOT NULL DEFAULT '',
    external_sku        VARCHAR(160),
    external_ref        VARCHAR(160),                    -- platform detail needed to update the offer (Shopify inventory item, Amazon product type)
    title               VARCHAR(500) NOT NULL DEFAULT '',
    image_url           TEXT,
    price_minor         BIGINT,
    compare_at_minor    BIGINT,
    quantity            INTEGER,
    stock_sync          BOOLEAN NOT NULL DEFAULT FALSE,   -- keep this channel's quantity equal to the shop's stock (off: the owner sets it per channel)
    currency            VARCHAR(3) NOT NULL DEFAULT 'EGP',
    status              VARCHAR(10) NOT NULL DEFAULT 'SYNCED' CHECK (status IN ('SYNCED', 'PENDING', 'ERROR')),
    last_error          VARCHAR(500),
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, external_product_id, external_variant_id)
);
CREATE INDEX idx_listings_variant ON integrations.listings (variant_id) WHERE variant_id IS NOT NULL;
CREATE INDEX idx_listings_tenant ON integrations.listings (tenant_id, account_id);

-- Orders that customers placed on the external channel. Kept apart from commerce.orders on purpose: they never touch the shop's own stock or notifications.
CREATE TABLE integrations.orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    account_id      UUID NOT NULL REFERENCES integrations.accounts(id) ON DELETE CASCADE,
    external_id     VARCHAR(160) NOT NULL,
    number          VARCHAR(80) NOT NULL DEFAULT '',
    status          VARCHAR(60) NOT NULL DEFAULT '',
    stage           VARCHAR(12) NOT NULL DEFAULT 'NEW' CHECK (stage IN ('NEW', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED')),
    total_minor     BIGINT NOT NULL DEFAULT 0,
    currency        VARCHAR(3) NOT NULL DEFAULT 'EGP',
    customer_name   VARCHAR(200),
    customer_phone  VARCHAR(40),
    customer_email  VARCHAR(254),
    address         JSONB NOT NULL DEFAULT '{}',
    items           JSONB NOT NULL DEFAULT '[]',
    tracking_number VARCHAR(120),
    carrier         VARCHAR(80),
    placed_at       TIMESTAMPTZ,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, external_id)
);
CREATE INDEX idx_integration_orders_tenant ON integrations.orders (tenant_id, placed_at DESC);
CREATE INDEX idx_integration_orders_account ON integrations.orders (account_id, placed_at DESC);

-- Background work (import, order pull, price/stock push) claimed with FOR UPDATE SKIP LOCKED, retried with a growing delay.
CREATE TABLE integrations.jobs (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    account_id  UUID REFERENCES integrations.accounts(id) ON DELETE CASCADE,
    type        VARCHAR(30) NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}',
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts    INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_integration_jobs_due ON integrations.jobs (next_run_at) WHERE status = 'PENDING';
CREATE INDEX idx_integration_jobs_account ON integrations.jobs (account_id, created_at DESC);

-- Shipments created with a carrier account, for the shop's own orders or for orders from a channel.
CREATE TABLE integrations.shipments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    account_id        UUID NOT NULL REFERENCES integrations.accounts(id) ON DELETE CASCADE,
    order_id          UUID REFERENCES commerce.orders(id) ON DELETE SET NULL,
    channel_order_id  UUID REFERENCES integrations.orders(id) ON DELETE SET NULL,
    tracking_number   VARCHAR(120) NOT NULL,
    label_url         TEXT,
    status            VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    cod_minor         BIGINT NOT NULL DEFAULT 0,
    last_event        VARCHAR(300),
    last_event_at     TIMESTAMPTZ,
    created_by        UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, tracking_number)
);
CREATE INDEX idx_shipments_order ON integrations.shipments (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_shipments_tenant ON integrations.shipments (tenant_id, created_at DESC);

-- AI-written posts for the shop's Facebook page / Instagram account.
CREATE TABLE marketing.posts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    product_id    UUID REFERENCES commerce.products(id) ON DELETE SET NULL,
    kind          VARCHAR(8) NOT NULL DEFAULT 'PRODUCT' CHECK (kind IN ('PRODUCT', 'SHOP')),
    channels      TEXT[] NOT NULL DEFAULT '{}',
    caption       TEXT NOT NULL DEFAULT '',
    image_file_id UUID,
    image_url     TEXT,
    status        VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SCHEDULED', 'POSTING', 'POSTED', 'PARTIAL', 'FAILED')),
    scheduled_at  TIMESTAMPTZ,
    posted_at     TIMESTAMPTZ,
    results       JSONB NOT NULL DEFAULT '[]',
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_posts_tenant ON marketing.posts (tenant_id, created_at DESC);
CREATE INDEX idx_posts_due ON marketing.posts (scheduled_at) WHERE status = 'SCHEDULED';

-- Ad campaigns created from the dashboard (Meta Marketing API).
CREATE TABLE marketing.campaigns (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    account_id          UUID NOT NULL REFERENCES integrations.accounts(id) ON DELETE CASCADE,
    product_id          UUID REFERENCES commerce.products(id) ON DELETE SET NULL,
    external_id         VARCHAR(80),
    name                VARCHAR(200) NOT NULL,
    objective           VARCHAR(30) NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'FAILED', 'ENDED')),
    daily_budget_minor  BIGINT NOT NULL DEFAULT 0,
    currency            VARCHAR(3) NOT NULL DEFAULT 'EGP',
    starts_at           TIMESTAMPTZ,
    ends_at             TIMESTAMPTZ,
    params              JSONB NOT NULL DEFAULT '{}',
    suggestion          JSONB NOT NULL DEFAULT '{}',
    insights            JSONB NOT NULL DEFAULT '{}',
    last_error          VARCHAR(500),
    last_synced_at      TIMESTAMPTZ,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaigns_tenant ON marketing.campaigns (tenant_id, created_at DESC);

INSERT INTO core.permissions (code, name) VALUES
 ('integration.read', 'View connected sales channels'), ('integration.manage', 'Connect and manage sales channels'),
 ('marketing.read', 'View social posts and ad campaigns'), ('marketing.manage', 'Create social posts and ad campaigns'),
 ('carrier.read', 'View shipping carriers and shipments'), ('carrier.manage', 'Connect carriers and create shipments');

INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL
  AND p.code IN ('integration.read', 'integration.manage', 'marketing.read', 'marketing.manage', 'carrier.read', 'carrier.manage')
ON CONFLICT DO NOTHING;
-- Wave 3: purchasing, stock transfers, POS support, tax invoices, public API and webhooks, blog, translations.

CREATE TABLE commerce.counters (
    tenant_id UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    kind      VARCHAR(12) NOT NULL,
    year      INTEGER NOT NULL,
    seq       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, kind, year)
);

CREATE TABLE commerce.suppliers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name       VARCHAR(150) NOT NULL,
    phone      VARCHAR(30),
    email      VARCHAR(150),
    notes      VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppliers_tenant ON commerce.suppliers (tenant_id, name);

CREATE TABLE commerce.purchase_orders (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    supplier_id UUID REFERENCES commerce.suppliers(id) ON DELETE SET NULL,
    number      VARCHAR(30) NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ORDERED', 'RECEIVED', 'CANCELLED')),
    branch_id   UUID REFERENCES commerce.branches(id),
    notes       VARCHAR(500) NOT NULL DEFAULT '',
    expected_at DATE,
    total_minor BIGINT NOT NULL DEFAULT 0,
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ordered_at  TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    UNIQUE (tenant_id, number)
);
CREATE INDEX idx_po_tenant ON commerce.purchase_orders (tenant_id, created_at DESC);

CREATE TABLE commerce.purchase_order_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id     UUID NOT NULL REFERENCES commerce.purchase_orders(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    variant_id      UUID NOT NULL REFERENCES commerce.product_variants(id),
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    unit_cost_minor BIGINT NOT NULL DEFAULT 0 CHECK (unit_cost_minor >= 0)
);
CREATE INDEX idx_po_items ON commerce.purchase_order_items (purchase_id);

CREATE TABLE commerce.stock_transfers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    variant_id  UUID NOT NULL REFERENCES commerce.product_variants(id),
    from_branch UUID NOT NULL REFERENCES commerce.branches(id),
    to_branch   UUID NOT NULL REFERENCES commerce.branches(id),
    quantity    INTEGER NOT NULL CHECK (quantity > 0),
    note        VARCHAR(300) NOT NULL DEFAULT '',
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_branch <> to_branch)
);
CREATE INDEX idx_transfers_tenant ON commerce.stock_transfers (tenant_id, created_at DESC);

-- Tax invoices and credit notes: numbered without gaps per shop and year, never edited after they are issued.
CREATE TABLE commerce.invoices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    number             VARCHAR(30) NOT NULL,
    kind               VARCHAR(12) NOT NULL CHECK (kind IN ('INVOICE', 'CREDIT_NOTE')),
    order_id           UUID NOT NULL REFERENCES commerce.orders(id),
    related_invoice_id UUID REFERENCES commerce.invoices(id),
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    currency           VARCHAR(3) NOT NULL,
    subtotal_minor     BIGINT NOT NULL,
    discount_minor     BIGINT NOT NULL DEFAULT 0,
    shipping_minor     BIGINT NOT NULL DEFAULT 0,
    total_minor        BIGINT NOT NULL,
    vat_percent        NUMERIC(5, 2) NOT NULL DEFAULT 0,
    vat_minor          BIGINT NOT NULL DEFAULT 0,
    vat_included       BOOLEAN NOT NULL DEFAULT TRUE,
    seller_name        VARCHAR(200) NOT NULL DEFAULT '',
    seller_tax_id      VARCHAR(60) NOT NULL DEFAULT '',
    seller_address     VARCHAR(300) NOT NULL DEFAULT '',
    buyer_name         VARCHAR(150) NOT NULL DEFAULT '',
    buyer_phone        VARCHAR(30) NOT NULL DEFAULT '',
    buyer_address      VARCHAR(400) NOT NULL DEFAULT '',
    lines              JSONB NOT NULL DEFAULT '[]',
    reason             VARCHAR(300),
    created_by         UUID,
    UNIQUE (tenant_id, number)
);
CREATE UNIQUE INDEX uq_invoice_per_order ON commerce.invoices (order_id) WHERE kind = 'INVOICE';
CREATE UNIQUE INDEX uq_credit_note_per_order ON commerce.invoices (order_id) WHERE kind = 'CREDIT_NOTE';
CREATE INDEX idx_invoices_tenant ON commerce.invoices (tenant_id, issued_at DESC);

-- Public API keys (read only, shown once) and outbound webhooks.
CREATE TABLE commerce.api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name         VARCHAR(80) NOT NULL,
    prefix       VARCHAR(16) NOT NULL,
    key_hash     VARCHAR(64) NOT NULL UNIQUE,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_tenant ON commerce.api_keys (tenant_id);

CREATE TABLE commerce.webhooks (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    url        VARCHAR(500) NOT NULL,
    secret_enc TEXT NOT NULL,
    events     TEXT[] NOT NULL DEFAULT '{order.created,order.status_changed}',
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhooks_tenant ON commerce.webhooks (tenant_id);

CREATE TABLE commerce.webhook_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    webhook_id      UUID NOT NULL REFERENCES commerce.webhooks(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    event           VARCHAR(40) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_code   INTEGER,
    last_error      VARCHAR(300),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX idx_deliveries_due ON commerce.webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_deliveries_webhook ON commerce.webhook_deliveries (webhook_id, id DESC);

CREATE TABLE commerce.blog_posts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    slug         VARCHAR(120) NOT NULL,
    title        VARCHAR(200) NOT NULL,
    excerpt      VARCHAR(400) NOT NULL DEFAULT '',
    body         TEXT NOT NULL DEFAULT '',
    published    BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

-- Product name and description in a second language (English next to the Arabic original).
CREATE TABLE commerce.product_translations (
    product_id  UUID NOT NULL REFERENCES commerce.products(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    locale      VARCHAR(5) NOT NULL,
    name        VARCHAR(250) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (product_id, locale)
);

INSERT INTO core.permissions (code, name) VALUES
 ('pos.use', 'Use the point of sale'), ('purchase.manage', 'Suppliers, purchase orders and stock transfers'), ('invoice.manage', 'Issue tax invoices and credit notes'),
 ('api.manage', 'API keys and webhooks'), ('blog.manage', 'Write blog posts and translate products');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code IN ('pos.use', 'purchase.manage', 'invoice.manage', 'api.manage', 'blog.manage')
ON CONFLICT DO NOTHING;
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_MANAGER') AND r.tenant_id IS NULL AND p.code IN ('pos.use', 'purchase.manage', 'invoice.manage')
ON CONFLICT DO NOTHING;

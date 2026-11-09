-- Domain registration & management: buy a domain inside the dashboard (wallet-funded, ResellerClub behind a
-- provider interface, disabled by default). The existing connect-a-domain flow (core.tenant_domains, DomainService)
-- is untouched; a purchased domain is still just a tenant_domains row, with two new nullable columns.

-- Platform-admin markup configuration: no price is ever hardcoded in code.
CREATE TABLE commerce.domain_price_rules (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tld               VARCHAR(20) NOT NULL UNIQUE CHECK (tld = lower(tld)),
    markup_kind       VARCHAR(10) NOT NULL DEFAULT 'PERCENT' CHECK (markup_kind IN ('FIXED', 'PERCENT')),
    markup_value      BIGINT NOT NULL DEFAULT 0 CHECK (markup_value >= 0),   -- FIXED: piasters added; PERCENT: whole percent added
    currency          VARCHAR(3) NOT NULL DEFAULT 'EGP',
    promo_price_minor BIGINT,                                                -- when set (and not expired) this register price is charged instead of cost+markup
    promo_expires_at  TIMESTAMPTZ,
    active            BOOLEAN NOT NULL DEFAULT TRUE,                         -- inactive TLDs are not offered in search
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A handful of common TLDs to start with so search works out of the box; the admin can add/edit/disable any.
INSERT INTO commerce.domain_price_rules (tld, markup_kind, markup_value) VALUES
    ('com', 'PERCENT', 20), ('net', 'PERCENT', 20), ('org', 'PERCENT', 20),
    ('store', 'PERCENT', 20), ('shop', 'PERCENT', 20), ('online', 'PERCENT', 20);

-- A priced, time-boxed search result: a UI convenience and a price snapshot only, never trusted blindly at
-- purchase time (the order re-checks availability and re-prices against the provider live).
CREATE TABLE commerce.domain_quotes (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    domain               VARCHAR(253) NOT NULL,
    tld                  VARCHAR(20) NOT NULL,
    available            BOOLEAN NOT NULL,
    premium              BOOLEAN NOT NULL DEFAULT FALSE,
    price_minor          BIGINT NOT NULL,
    renewal_price_minor  BIGINT NOT NULL,
    provider_price_minor BIGINT NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'EGP',
    expires_at           TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_domain_quotes_tenant ON commerce.domain_quotes (tenant_id, created_at DESC);

-- WHOIS / registrant details a tenant enters once and reuses across orders. Never fabricated or defaulted.
CREATE TABLE commerce.registrant_contacts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    org         VARCHAR(200),
    email       VARCHAR(200) NOT NULL,
    phone       VARCHAR(30) NOT NULL,
    country     VARCHAR(2) NOT NULL,
    address     VARCHAR(400) NOT NULL,
    city        VARCHAR(100),
    state       VARCHAR(100),
    postal_code VARCHAR(20),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_registrant_contacts_tenant ON commerce.registrant_contacts (tenant_id, created_at DESC);

-- The purchase/registration state machine: one row per attempt (a renewal is its own row, kind=RENEWAL).
CREATE TABLE commerce.domain_orders (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    domain                VARCHAR(253) NOT NULL,
    kind                  VARCHAR(10) NOT NULL DEFAULT 'REGISTER' CHECK (kind IN ('REGISTER', 'RENEWAL')),
    years                 INTEGER NOT NULL DEFAULT 1 CHECK (years BETWEEN 1 AND 10),
    price_minor           BIGINT NOT NULL,
    provider_price_minor  BIGINT,                 -- the registrar's own cost at purchase/renewal time, for the admin margin report
    currency              VARCHAR(3) NOT NULL DEFAULT 'EGP',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT'
                          CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'REGISTERING', 'DNS_CONFIGURING', 'VERIFYING', 'LIVE', 'FAILED', 'REFUNDED', 'CANCELLED')),
    wallet_ref            VARCHAR(100) UNIQUE,
    provider_order_id     VARCHAR(100),
    registrant_contact_id UUID REFERENCES commerce.registrant_contacts(id),
    tenant_domain_id      UUID REFERENCES core.tenant_domains(id),
    attempts              INTEGER NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error            VARCHAR(300),
    -- 'UNCERTAIN' is not a registrar error code: it means a registration/renewal call timed out or answered ambiguously and the
    -- runner is confirming the real outcome with the registrar (getDomainDetails) before deciding anything - never a guess.
    last_error_code       VARCHAR(40),
    expires_at            TIMESTAMPTZ,            -- the registration's own expiry once known from the provider
    renewal_notified_at   TIMESTAMPTZ,
    -- Renewal is opt-in only: the expiry job NEVER auto-charges a domain unless the merchant explicitly turned this on; it is
    -- off by default and only ever flipped on by the merchant's own PUT .../orders/{id}/auto-renew call.
    auto_renew            BOOLEAN NOT NULL DEFAULT FALSE,
    created_by            UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- blocks two concurrent purchases of the same name by the same tenant while one is still in flight. Deliberately
-- excludes LIVE (not just the terminal failure states): once a domain is actually registered, renewal orders for
-- that same (tenant_id, domain) must keep being insertable - only an in-flight REGISTER/RENEWAL attempt is unique.
CREATE UNIQUE INDEX uq_domain_orders_tenant_domain_active ON commerce.domain_orders (tenant_id, domain)
    WHERE status IN ('PENDING_PAYMENT', 'PAID', 'REGISTERING', 'DNS_CONFIGURING', 'VERIFYING');
CREATE INDEX idx_domain_orders_tenant ON commerce.domain_orders (tenant_id, created_at DESC);
CREATE INDEX idx_domain_orders_due ON commerce.domain_orders (next_attempt_at)
    WHERE status IN ('PAID', 'REGISTERING', 'DNS_CONFIGURING', 'VERIFYING');
CREATE INDEX idx_domain_orders_expiring ON commerce.domain_orders (expires_at) WHERE status = 'LIVE';

-- Append-only audit trail per order (mirrors commerce.order_status_history).
CREATE TABLE commerce.domain_order_events (
    id         BIGSERIAL PRIMARY KEY,
    order_id   UUID NOT NULL REFERENCES commerce.domain_orders(id) ON DELETE CASCADE,
    status     VARCHAR(20) NOT NULL,
    note       VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_domain_order_events_order ON commerce.domain_order_events (order_id, id);

-- Recent provider errors for troubleshooting (admin screen).
CREATE TABLE commerce.domain_provider_errors (
    id         BIGSERIAL PRIMARY KEY,
    order_id   UUID REFERENCES commerce.domain_orders(id) ON DELETE SET NULL,
    domain     VARCHAR(253),
    operation  VARCHAR(40) NOT NULL,
    code       VARCHAR(40) NOT NULL,
    message    VARCHAR(500),
    retryable  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_domain_provider_errors_created ON commerce.domain_provider_errors (created_at DESC);

-- Domain purchases/renewals are billed through the same wallet ledger as everything else ("DOMAIN" kind, refunds
-- still use the existing "REFUND" kind) - never a second payment path.
ALTER TABLE billing.wallet_entries DROP CONSTRAINT IF EXISTS wallet_entries_kind_check;
ALTER TABLE billing.wallet_entries ADD CONSTRAINT wallet_entries_kind_check CHECK (kind IN ('TOPUP', 'MESSAGE', 'REFUND', 'ADJUST', 'GIFT', 'AI', 'DOMAIN'));

-- core.tenant_domains: a purchased domain is still just a row here. Existing read paths, makePrimary, remove,
-- demoteStoppedPrimaries all keep working untouched; they simply see source='CONNECTED' for every domain added
-- the old way.
ALTER TABLE core.tenant_domains ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'CONNECTED' CHECK (source IN ('CONNECTED', 'PURCHASED'));
ALTER TABLE core.tenant_domains ADD COLUMN domain_order_id UUID REFERENCES commerce.domain_orders(id);

-- New merchant-facing permission, seeded the same way as every other feature permission (V45): the roles that
-- already hold domain.manage today (V2 STORE_OWNER/CLINIC_OWNER, V71 LAWYER_OWNER) get it too.
INSERT INTO core.permissions (code, name) VALUES ('domains.purchase', 'Buy and manage domain purchases');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'CLINIC_OWNER', 'LAWYER_OWNER') AND r.tenant_id IS NULL AND p.code = 'domains.purchase'
ON CONFLICT DO NOTHING;

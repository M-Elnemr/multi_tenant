-- Affiliate program, phase 2: marketers have their own accounts and a marketplace of shops; payouts are requests the shop settles itself;
-- reviews, chat, disputes, notifications; fraud flags and an audit trail for the platform. The platform still never holds money.

CREATE TABLE affiliate.accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone         VARCHAR(20) NOT NULL UNIQUE,
    email         VARCHAR(254),
    name          VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    bio           VARCHAR(600),
    city          VARCHAR(80),
    channels      JSONB NOT NULL DEFAULT '[]'::jsonb,        -- [{"kind":"instagram","url":"..."}]
    payout_note   VARCHAR(300),                               -- how this marketer wants the shop to pay (text only; the platform never pays)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE affiliate.sessions (
    token_hash  VARCHAR(64) PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES affiliate.accounts(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_aff_sessions_account ON affiliate.sessions (account_id);

ALTER TABLE affiliate.programs ADD COLUMN listed BOOLEAN NOT NULL DEFAULT false;          -- appears in the marketplace
ALTER TABLE affiliate.programs ADD COLUMN auto_approve BOOLEAN NOT NULL DEFAULT false;    -- applications are approved at once
ALTER TABLE affiliate.programs ADD COLUMN pitch VARCHAR(300);                              -- one line shown in the marketplace

ALTER TABLE affiliate.partners DROP CONSTRAINT partners_status_check;
ALTER TABLE affiliate.partners ADD CONSTRAINT partners_status_check CHECK (status IN ('PENDING','ACTIVE','PAUSED','REJECTED','BLOCKED'));
ALTER TABLE affiliate.partners ADD COLUMN account_id UUID REFERENCES affiliate.accounts(id);
ALTER TABLE affiliate.partners ADD COLUMN message VARCHAR(500);                            -- the applicant's note to the shop
ALTER TABLE affiliate.partners ADD COLUMN decided_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_aff_partner_account ON affiliate.partners (tenant_id, account_id) WHERE account_id IS NOT NULL;

ALTER TABLE affiliate.visits ADD COLUMN campaign VARCHAR(40);
ALTER TABLE affiliate.commissions ADD COLUMN campaign VARCHAR(40);
ALTER TABLE affiliate.commissions ADD COLUMN payout_request_id UUID;
ALTER TABLE affiliate.commissions ADD COLUMN offer_id UUID;

-- time-limited rates: for one marketer or for everybody of a shop; the highest applicable rate wins over the marketer's own and the program's
CREATE TABLE affiliate.offers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id UUID REFERENCES affiliate.partners(id) ON DELETE CASCADE,   -- NULL = every marketer of the shop
    title      VARCHAR(120) NOT NULL,
    percent    NUMERIC(5,2) NOT NULL CHECK (percent >= 0 AND percent <= 50),
    starts_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at    TIMESTAMPTZ,
    active     BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aff_offers ON affiliate.offers (tenant_id, active);

CREATE TABLE affiliate.payout_requests (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES affiliate.partners(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    status       VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','PAID','REJECTED','CANCELLED')),
    note         VARCHAR(300),                 -- the marketer's note
    evidence     VARCHAR(500),                 -- the shop's proof of payment (reference, receipt link); text only
    decision_note VARCHAR(300),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);
CREATE INDEX idx_aff_payouts ON affiliate.payout_requests (tenant_id, status);
ALTER TABLE affiliate.commissions ADD CONSTRAINT fk_aff_comm_payout FOREIGN KEY (payout_request_id) REFERENCES affiliate.payout_requests(id);
ALTER TABLE affiliate.ledger DROP CONSTRAINT ledger_kind_check;
ALTER TABLE affiliate.ledger ADD CONSTRAINT ledger_kind_check CHECK (kind IN ('ACCRUE','APPROVE','REVERSE','PAYOUT','LOCK','UNLOCK'));

CREATE TABLE affiliate.reviews (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id UUID NOT NULL REFERENCES affiliate.partners(id) ON DELETE CASCADE,
    direction  VARCHAR(16) NOT NULL CHECK (direction IN ('SHOP_TO_MARKETER','MARKETER_TO_SHOP')),
    rating     SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment    VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, partner_id, direction)
);

CREATE TABLE affiliate.messages (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id UUID NOT NULL REFERENCES affiliate.partners(id) ON DELETE CASCADE,
    sender     VARCHAR(10) NOT NULL CHECK (sender IN ('SHOP','MARKETER')),
    body       VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);
CREATE INDEX idx_aff_messages ON affiliate.messages (tenant_id, partner_id, id);

CREATE TABLE affiliate.disputes (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id    UUID NOT NULL REFERENCES affiliate.partners(id) ON DELETE CASCADE,
    commission_id UUID REFERENCES affiliate.commissions(id),
    opened_by     VARCHAR(10) NOT NULL CHECK (opened_by IN ('SHOP','MARKETER')),
    reason        VARCHAR(600) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
    resolution    VARCHAR(600),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ
);
CREATE INDEX idx_aff_disputes ON affiliate.disputes (tenant_id, status);

CREATE TABLE affiliate.notifications (
    id         BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES affiliate.accounts(id) ON DELETE CASCADE,
    kind       VARCHAR(30) NOT NULL,
    text       VARCHAR(300) NOT NULL,
    tenant_id  UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);
CREATE INDEX idx_aff_notif ON affiliate.notifications (account_id, id);

CREATE TABLE affiliate.fraud_flags (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID REFERENCES core.tenants(id) ON DELETE CASCADE,
    partner_id    UUID REFERENCES affiliate.partners(id) ON DELETE CASCADE,
    commission_id UUID REFERENCES affiliate.commissions(id),
    kind          VARCHAR(20) NOT NULL,
    severity      VARCHAR(6) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    detail        VARCHAR(400) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DISMISSED','CONFIRMED')),
    review_note   VARCHAR(300),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at   TIMESTAMPTZ
);
CREATE INDEX idx_aff_flags ON affiliate.fraud_flags (status, created_at);
CREATE UNIQUE INDEX uq_aff_flag_once ON affiliate.fraud_flags (kind, partner_id, coalesce(commission_id, '00000000-0000-0000-0000-000000000000')) WHERE status = 'OPEN';

CREATE TABLE affiliate.audit (
    id         BIGSERIAL PRIMARY KEY,
    actor_type VARCHAR(10) NOT NULL,           -- SHOP, MARKETER, PLATFORM, SYSTEM
    actor_id   UUID,
    tenant_id  UUID,
    partner_id UUID,
    action     VARCHAR(40) NOT NULL,
    detail     VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aff_audit ON affiliate.audit (created_at);
CREATE INDEX idx_aff_audit_tenant ON affiliate.audit (tenant_id, created_at);

-- platform-wide switches (one row)
CREATE TABLE affiliate.settings (
    id                 SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    registration_open  BOOLEAN NOT NULL DEFAULT true,
    marketplace_open   BOOLEAN NOT NULL DEFAULT true,
    max_percent        NUMERIC(5,2) NOT NULL DEFAULT 50 CHECK (max_percent BETWEEN 1 AND 50),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO affiliate.settings (id) VALUES (1);

INSERT INTO core.permissions (code, name) VALUES ('affiliate.admin', 'Oversee the affiliate program across the platform');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('PLATFORM_ADMIN', 'PLATFORM_OWNER') AND r.tenant_id IS NULL AND p.code = 'affiliate.admin'
ON CONFLICT DO NOTHING;

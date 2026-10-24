-- AI seller memory and follow-ups, WhatsApp daily report, Copilot history, marketing autopilot.

-- ---- AI seller ------------------------------------------------------------------------------------------------
-- Questions the assistant could not answer (silent flag for the owner; the customer is never handed to anyone).
CREATE TABLE chat.unanswered (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES chat.conversations(id) ON DELETE SET NULL,
    question        VARCHAR(600) NOT NULL,
    resolved        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_unanswered_tenant ON chat.unanswered (tenant_id, resolved, created_at DESC);

-- What the assistant remembers about one customer (size, taste, name...), per shop and channel identity.
CREATE TABLE chat.customer_memory (
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    channel     VARCHAR(12) NOT NULL,
    external_id VARCHAR(120) NOT NULL,
    notes       VARCHAR(1500) NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, channel, external_id)
);

-- Messages the model answered, per shop and month: the basis for quotas and the cost view.
CREATE TABLE chat.usage (
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    month       DATE NOT NULL,
    ai_messages INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, month)
);

-- A follow-up message the assistant promised ("I'll remind you tomorrow"); sent only while the channel's 24 h window is open.
CREATE TABLE chat.follow_ups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    due_at          TIMESTAMPTZ NOT NULL,
    body            VARCHAR(800) NOT NULL,
    status          VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'SKIPPED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_follow_ups_due ON chat.follow_ups (due_at) WHERE status = 'PENDING';

-- "Tell me when it is back": one row per customer and variant.
CREATE TABLE chat.stock_alerts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    variant_id      UUID NOT NULL REFERENCES commerce.product_variants(id) ON DELETE CASCADE,
    status          VARCHAR(8) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING', 'SENT', 'SKIPPED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, variant_id)
);

-- ---- WhatsApp daily report -------------------------------------------------------------------------------------
CREATE SCHEMA whatsapp;

-- One linked WhatsApp number per shop. The shop messaged our number first, so the 24 h window is open until last_inbound_at + 24 h.
CREATE TABLE whatsapp.contacts (
    tenant_id       UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    phone           VARCHAR(20) NOT NULL UNIQUE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    send_after      TIME NOT NULL DEFAULT '23:59',
    language        VARCHAR(2) NOT NULL DEFAULT 'ar' CHECK (language IN ('ar', 'en')),
    last_inbound_at TIMESTAMPTZ NOT NULL,
    needs_ping      BOOLEAN NOT NULL DEFAULT FALSE,
    linked_by       UUID,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp.link_codes (
    code        VARCHAR(12) PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    created_by  UUID,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX idx_link_codes_tenant ON whatsapp.link_codes (tenant_id);

CREATE TABLE whatsapp.report_log (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    day        DATE NOT NULL,
    status     VARCHAR(16) NOT NULL CHECK (status IN ('SENT', 'WINDOW_CLOSED', 'FAILED')),
    message_id VARCHAR(120),
    error      VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, day)
);

-- ---- Copilot ------------------------------------------------------------------------------------------------------
CREATE SCHEMA copilot;

CREATE TABLE copilot.messages (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL,
    role       VARCHAR(10) NOT NULL CHECK (role IN ('user', 'assistant')),
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_copilot_messages ON copilot.messages (tenant_id, user_id, id DESC);

-- ---- Marketing autopilot -------------------------------------------------------------------------------------------
CREATE TABLE marketing.plan_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    day           DATE NOT NULL,
    kind          VARCHAR(8) NOT NULL DEFAULT 'PRODUCT' CHECK (kind IN ('PRODUCT', 'SHOP')),
    product_id    UUID REFERENCES commerce.products(id) ON DELETE SET NULL,
    topic         VARCHAR(200) NOT NULL DEFAULT '',
    caption       TEXT NOT NULL DEFAULT '',
    channels      TEXT[] NOT NULL DEFAULT '{}',
    want_video    BOOLEAN NOT NULL DEFAULT FALSE,
    video_path    VARCHAR(300),
    status        VARCHAR(10) NOT NULL DEFAULT 'SUGGESTED' CHECK (status IN ('SUGGESTED', 'APPROVED', 'SCHEDULED', 'SKIPPED', 'POSTED', 'FAILED')),
    post_id       UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_items_tenant_day ON marketing.plan_items (tenant_id, day);

-- Hard limits the optimizer can never cross, per shop. Off until the owner switches it on.
CREATE TABLE marketing.guardrails (
    tenant_id         UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    daily_cap_minor   BIGINT NOT NULL DEFAULT 0 CHECK (daily_cap_minor >= 0),
    monthly_cap_minor BIGINT NOT NULL DEFAULT 0 CHECK (monthly_cap_minor >= 0),
    max_step_pct      INTEGER NOT NULL DEFAULT 20 CHECK (max_step_pct BETWEEN 5 AND 50),
    target_cpc_minor  BIGINT NOT NULL DEFAULT 0 CHECK (target_cpc_minor >= 0),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Everything the optimizer did, so the owner can see it and undo it.
CREATE TABLE marketing.ad_actions (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    campaign_id  UUID NOT NULL REFERENCES marketing.campaigns(id) ON DELETE CASCADE,
    kind         VARCHAR(16) NOT NULL CHECK (kind IN ('PAUSE', 'RAISE_BUDGET', 'LOWER_BUDGET', 'PAUSE_VARIANT', 'KEEP_VARIANT')),
    reason       VARCHAR(400) NOT NULL,
    before_value BIGINT,
    after_value  BIGINT,
    ref          VARCHAR(80),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    undone_at    TIMESTAMPTZ
);
CREATE INDEX idx_ad_actions_tenant ON marketing.ad_actions (tenant_id, created_at DESC);

-- Several Arabic texts for one campaign, each its own ad; after a few days the cheapest per click is kept.
CREATE TABLE marketing.copy_variants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    campaign_id    UUID NOT NULL REFERENCES marketing.campaigns(id) ON DELETE CASCADE,
    headline       VARCHAR(80) NOT NULL,
    primary_text   VARCHAR(400) NOT NULL,
    ad_external_id VARCHAR(80),
    status         VARCHAR(8) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'WINNER', 'PAUSED')),
    insights       JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_copy_variants_campaign ON marketing.copy_variants (campaign_id);

INSERT INTO core.permissions (code, name) VALUES ('copilot.use', 'Ask the business copilot and read its daily advice');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code = 'copilot.use'
ON CONFLICT DO NOTHING;

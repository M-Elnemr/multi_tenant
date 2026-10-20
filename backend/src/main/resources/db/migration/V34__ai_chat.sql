-- AI sales assistant: per-shop settings, taught lessons (per shop or global), conversations and messages ---------------
CREATE SCHEMA IF NOT EXISTS chat;

CREATE TABLE chat.settings (
    tenant_id        UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    tone             VARCHAR(20) NOT NULL DEFAULT 'FRIENDLY' CHECK (tone IN ('FRIENDLY', 'FORMAL', 'SHORT')),
    max_discount_pct INTEGER NOT NULL DEFAULT 0 CHECK (max_discount_pct BETWEEN 0 AND 50),
    greeting         VARCHAR(300),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- tenant_id NULL = a global lesson written by the platform admin; otherwise it belongs to that shop only.
CREATE TABLE chat.lessons (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID REFERENCES core.tenants(id) ON DELETE CASCADE,
    instruction      VARCHAR(1000) NOT NULL,
    example_question VARCHAR(1000),
    example_answer   VARCHAR(2000),
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lessons_tenant_idx ON chat.lessons (tenant_id, created_at DESC);

CREATE TABLE chat.conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    channel     VARCHAR(12) NOT NULL CHECK (channel IN ('WEB', 'TEST', 'FACEBOOK', 'INSTAGRAM', 'WHATSAPP')),
    external_id VARCHAR(120) NOT NULL,           -- WEB/TEST: a random id kept by the browser; Meta channels: the sender id
    client_id   UUID,                            -- the signed-in shop client, when there was one
    pending     JSONB,                           -- the order summary the customer was shown and has not yet confirmed
    last_order  VARCHAR(40),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel, external_id)
);

CREATE TABLE chat.messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_conv_idx ON chat.messages (conversation_id, id);

-- Orders made through the assistant are tagged.
ALTER TABLE commerce.orders ADD COLUMN source VARCHAR(12) NOT NULL DEFAULT 'WEB';

INSERT INTO core.permissions (code, name) VALUES
 ('ai.read', 'View the AI assistant'), ('ai.manage', 'Set up and teach the AI assistant'),
 ('platform.chat.teach', 'Teach the AI assistant for every shop (platform)');

INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code IN ('ai.read', 'ai.manage')
ON CONFLICT DO NOTHING;

INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('PLATFORM_OWNER', 'PLATFORM_ADMIN') AND r.tenant_id IS NULL AND p.code = 'platform.chat.teach'
ON CONFLICT DO NOTHING;

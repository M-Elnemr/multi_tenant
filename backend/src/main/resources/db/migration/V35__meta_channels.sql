-- Facebook / Instagram / WhatsApp connections for the AI assistant (development-mode testing first) --------------
CREATE TABLE chat.channels (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform    VARCHAR(12) NOT NULL CHECK (platform IN ('FACEBOOK', 'INSTAGRAM', 'WHATSAPP')),
    external_id VARCHAR(80) NOT NULL,            -- Facebook page id, Instagram account id, or WhatsApp phone-number id
    name        VARCHAR(200),
    token_enc   TEXT NOT NULL,                   -- access token, AES-GCM encrypted (never returned by the API)
    status      VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, external_id)
);
CREATE INDEX channels_tenant_idx ON chat.channels (tenant_id);

-- Meta may deliver the same message more than once: remember what was handled.
CREATE TABLE chat.meta_events (
    mid        VARCHAR(250) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

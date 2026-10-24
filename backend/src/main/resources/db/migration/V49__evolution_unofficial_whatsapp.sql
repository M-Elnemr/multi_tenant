-- Optional "use my own WhatsApp number" mode (unofficial, through a self-hosted Evolution API). Off for every shop until its owner turns it on and accepts the warning.
CREATE TABLE chat.evolution_settings (
    tenant_id   UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    instance    VARCHAR(60) NOT NULL UNIQUE,
    status      VARCHAR(16) NOT NULL DEFAULT 'DISCONNECTED' CHECK (status IN ('DISCONNECTED', 'CONNECTING', 'CONNECTED')),
    phone       VARCHAR(30),
    accepted_at TIMESTAMPTZ,
    accepted_by UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

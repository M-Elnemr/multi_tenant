-- Snapchat (official Marketing API): the shop's login and the catalog + scheduled product feed made for it.
CREATE TABLE integrations.snapchat_links (
    tenant_id         UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    token_enc         TEXT NOT NULL,                      -- access + refresh token, encrypted
    organizations     JSONB NOT NULL DEFAULT '[]',        -- what the login can see: id, name, roles
    organization_id   VARCHAR(60),
    organization_name VARCHAR(200),
    catalog_id        VARCHAR(60),
    feed_id           VARCHAR(60),
    state             VARCHAR(20) NOT NULL DEFAULT 'CONNECTED' CHECK (state IN ('CONNECTED', 'CHOOSE_ORGANIZATION', 'APPROVAL_REQUIRED', 'REAUTH_REQUIRED', 'ERROR')),
    note              VARCHAR(300),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

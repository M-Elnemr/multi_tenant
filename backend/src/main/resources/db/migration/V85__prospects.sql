-- Round 5: "new customers" (prospects) targeting. People who follow or commented on the shop's Instagram / Facebook page but never wrote to it.
-- Messaged hand-style (route MANUAL_STYLE) with a stricter cold policy; the broadcast keeps a kind so the runner picks the right profile.
ALTER TABLE commerce.broadcasts
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'CUSTOMERS' CHECK (kind IN ('CUSTOMERS', 'PROSPECTS'));

CREATE TABLE commerce.prospects (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform      VARCHAR(12) NOT NULL CHECK (platform IN ('INSTAGRAM', 'FACEBOOK')),
    external_ref  VARCHAR(120) NOT NULL,                    -- Instagram pk / Facebook author id
    username      VARCHAR(120),
    display_name  VARCHAR(200),
    source        VARCHAR(10) NOT NULL CHECK (source IN ('FOLLOWER', 'COMMENTER')),
    post_ref      VARCHAR(200),
    last_comment  VARCHAR(500),
    is_private    BOOLEAN NOT NULL DEFAULT FALSE,
    status        VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'CONTACTED', 'REPLIED', 'OPTED_OUT', 'BLOCKED', 'NOT_FOUND')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    contacted_at  TIMESTAMPTZ,
    UNIQUE (tenant_id, platform, external_ref)
);
CREATE INDEX idx_prospects_list ON commerce.prospects (tenant_id, platform, source, status);

CREATE TABLE commerce.prospect_syncs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform    VARCHAR(12) NOT NULL CHECK (platform IN ('INSTAGRAM', 'FACEBOOK')),
    status      VARCHAR(10) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    fetched     INT NOT NULL DEFAULT 0,
    total       INT NOT NULL DEFAULT 0,
    error       VARCHAR(100),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_prospect_syncs_tenant ON commerce.prospect_syncs (tenant_id, platform, started_at DESC);

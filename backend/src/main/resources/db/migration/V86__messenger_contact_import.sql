-- Round 5: Messenger contacts imported through the unofficial (cookie) session. No chat.channels row is needed (conversations carry no channel id): an imported contact is a
-- chat.conversations row marked imported_at, with no messages. It counts as someone who wrote to the Page (for the manual-style guard) and stays out of the inbox until a real message exists.
ALTER TABLE chat.conversations ADD COLUMN imported_at TIMESTAMPTZ;

CREATE TABLE chat.contact_imports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform    VARCHAR(12) NOT NULL CHECK (platform IN ('FACEBOOK')),
    status      VARCHAR(10) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    fetched     INT NOT NULL DEFAULT 0,
    imported    INT NOT NULL DEFAULT 0,           -- new contacts (the rest were already there)
    error       VARCHAR(100),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_contact_imports_tenant ON chat.contact_imports (tenant_id, platform, started_at DESC);

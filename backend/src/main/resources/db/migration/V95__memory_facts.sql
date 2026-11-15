-- Structured memory of what a customer told the shop, one row per fact, with where it came from. Scope: one conversation (= one customer on one channel of one shop).
--   type          BUDGET | SIZE | COLOR | CONSTRAINT | NAME | INTENT
--   fact_key      what the fact is about, e.g. budget, size, color:black, constraint:leather; at most one ACTIVE fact per key (a newer explicit statement supersedes the older one)
--   confidence    1.0 stated outright, lower when it is a reading of what they said (shown as "inferred", never as confirmed)
--   status        ACTIVE | SUPERSEDED (a newer fact replaced it; kept for provenance) | EXPIRED
-- A fact is never shop data: prices, stock, variants and order state always come from the tools. Backward compatible: a new table only.
CREATE TABLE IF NOT EXISTS chat.memory_facts (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    type              VARCHAR(16) NOT NULL CHECK (type IN ('BUDGET', 'SIZE', 'COLOR', 'CONSTRAINT', 'NAME', 'INTENT')),
    fact_key          VARCHAR(80) NOT NULL,
    value             VARCHAR(200) NOT NULL,
    source_message_id BIGINT REFERENCES chat.messages(id) ON DELETE SET NULL,
    source_text       VARCHAR(240),
    confidence        REAL NOT NULL DEFAULT 1.0,
    status            VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'EXPIRED')),
    superseded_by     BIGINT REFERENCES chat.memory_facts(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_facts_active_key ON chat.memory_facts (conversation_id, fact_key) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_memory_facts_conv ON chat.memory_facts (conversation_id, status, type);

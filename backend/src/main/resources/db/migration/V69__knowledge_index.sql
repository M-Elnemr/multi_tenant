-- What the AI seller knows about each shop, for search by meaning (RAG): one row per product and per piece of shop text (policies, about, branches), with the text's
-- embedding. Prices and stock are never stored here: they change all the time and are always read from the catalog itself.
CREATE TABLE chat.knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('PRODUCT', 'SHOP')),
    source_id VARCHAR(270) NOT NULL,          -- the product's slug, or the name of the shop text («return_policy»)
    chunk_no INT NOT NULL DEFAULT 0,
    title VARCHAR(300) NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,        -- the body (and model) it was embedded from: a change re-embeds only that row
    embedding REAL[],                         -- NULL until embedded
    embed_model VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_type, source_id, chunk_no)
);
CREATE INDEX idx_knowledge_tenant ON chat.knowledge_chunks (tenant_id, source_type);
CREATE INDEX idx_knowledge_pending ON chat.knowledge_chunks (tenant_id) WHERE embedding IS NULL;

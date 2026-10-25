-- One row per AI request (a chat reply with all its model rounds, an instant reply, a copilot answer, a generated post...), so the platform admin can see where tokens go.
DROP TABLE IF EXISTS chat.ai_usage;
CREATE TABLE chat.ai_usage_log (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id       UUID,
    feature         VARCHAR(24) NOT NULL,
    channel         VARCHAR(16),
    conversation_id UUID,
    instant         BOOLEAN NOT NULL DEFAULT false,
    model_calls     INTEGER NOT NULL DEFAULT 0,
    input_tokens    INTEGER NOT NULL DEFAULT 0,
    output_tokens   INTEGER NOT NULL DEFAULT 0,
    cached_tokens   INTEGER NOT NULL DEFAULT 0,
    calls           JSONB NOT NULL DEFAULT '[]',
    preview         VARCHAR(80)
);
CREATE INDEX ai_usage_log_day ON chat.ai_usage_log (created_at);
CREATE INDEX ai_usage_log_tenant_day ON chat.ai_usage_log (tenant_id, created_at);
CREATE INDEX ai_usage_log_conv ON chat.ai_usage_log (conversation_id) WHERE conversation_id IS NOT NULL;

-- Tokens the assistant used per shop and day, whatever the model: shows what the AI costs and which shops use most.
CREATE TABLE IF NOT EXISTS chat.ai_usage (
    tenant_id     UUID NOT NULL,
    day           DATE NOT NULL,
    feature       VARCHAR(24) NOT NULL,
    replies       INTEGER NOT NULL DEFAULT 0,
    model_calls   INTEGER NOT NULL DEFAULT 0,
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cached_tokens BIGINT NOT NULL DEFAULT 0,
    instant       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, day, feature)
);

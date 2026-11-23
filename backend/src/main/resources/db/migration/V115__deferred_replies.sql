-- A customer message that no AI model could answer (every model failed or hung) is not answered with a «we are busy» text any more: the turn is kept here and retried in the background
-- (+15 s, +45 s, +2 min, +5 min after it was deferred) through the normal reply path, and the answer is delivered like any assistant reply. Only when the last retry fails does the customer get ONE short
-- holding message and the chat is flagged for the shop team.
--   status       PENDING (waiting for its next attempt) | DONE (answered, or no longer needed: SUPERSEDED / handled by a person) | HELD (every attempt failed: holding message and team flag)
--   attempts     retries that have run (0..4)
--   holding_sent TRUE for the row that sent the holding message (at most one per conversation in 12 hours)
-- The turn's points were taken when the customer wrote (once per message); a retry never takes them again. Backward compatible: a new table only; rollback: docs/rollback/V115_deferred_replies_rollback.sql.
CREATE TABLE IF NOT EXISTS chat.deferred_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    channel         VARCHAR(20) NOT NULL,
    external_id     TEXT NOT NULL,
    client_id       UUID,
    user_message_id BIGINT NOT NULL,
    message_text    TEXT NOT NULL,
    display_name    VARCHAR(120),
    status          VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'HELD')),
    attempts        SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    holding_sent    BOOLEAN NOT NULL DEFAULT FALSE,
    last_error      VARCHAR(300),
    note            VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_deferred_replies_due ON chat.deferred_replies (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_deferred_replies_conversation ON chat.deferred_replies (conversation_id, created_at);

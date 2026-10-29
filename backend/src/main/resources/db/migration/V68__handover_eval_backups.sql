-- Hand-over to the shop: a chat the assistant should not handle alone (an angry customer, someone asking for a person, the assistant failing) is marked for the owner,
-- who can read it, answer it themself and pause the assistant in that chat for a while.
ALTER TABLE chat.conversations
    ADD COLUMN needs_human        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN needs_human_reason VARCHAR(200),
    ADD COLUMN needs_human_at     TIMESTAMPTZ,
    ADD COLUMN ai_paused_until    TIMESTAMPTZ;
CREATE INDEX idx_conversations_inbox ON chat.conversations (tenant_id, updated_at DESC) WHERE channel <> 'TEST';
ALTER TABLE chat.messages ADD COLUMN by_owner BOOLEAN NOT NULL DEFAULT FALSE;   -- written by the shop team, not the assistant

-- The scheduled quality check: each run of the golden conversations on a model, so a drop against the last run raises an alert.
CREATE TABLE chat.ai_eval_runs (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    model       VARCHAR(12) NOT NULL,
    kind        VARCHAR(10) NOT NULL,          -- NIGHTLY (the core set) or WEEKLY (everything) or MANUAL
    overall_pct INT,
    checks      INT,
    summary     JSONB,
    status      VARCHAR(10) NOT NULL,          -- DONE or FAILED
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_eval_runs ON chat.ai_eval_runs (model, kind, finished_at DESC);

-- Each server backup (written by infra/scripts/backup.sh through psql): the watchdog warns when none succeeded for a day.
CREATE SCHEMA IF NOT EXISTS ops;
CREATE TABLE ops.backup_runs (
    id         BIGSERIAL PRIMARY KEY,
    kind       VARCHAR(12) NOT NULL,           -- BACKUP or RESTORE_TEST
    ok         BOOLEAN NOT NULL,
    size_bytes BIGINT,
    offsite    BOOLEAN NOT NULL DEFAULT FALSE, -- the copy also reached storage outside the server
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

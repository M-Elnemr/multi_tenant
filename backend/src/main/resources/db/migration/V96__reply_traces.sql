-- One row per customer reply with its measured timeline (burst wait, queue wait, every model attempt including the failed ones, hedges, fallback, tools, rule checks, rewrites, review, outbound).
-- Why a new table: chat.ai_events keeps one short text («detail», cut at 500 characters) and one number per event and has no trace id, so the stages of ONE reply cannot be joined, and
-- the failed model calls (the 27-second stall before the fallback) are not in it. A new table only: backward compatible, nothing existing is altered.
--   trace_id   random id of the inbound message (also in the log line «Reply trace: id=...»)
--   source     REPLY (a customer) | EVAL (the scheduled or manual quality evaluation) | TEST (an owner's test chat)
--   outcome    completed | failed
--   summary    totals per stage (see ReplyTrace.summarize); model time is summed from non-nesting provider_call events only
--   events     [{n: name, t: ms since the message arrived, d: ms (spans), a: {safe attributes}}]; never a message, prompt, answer, tool argument, phone number or key
-- Retention: 60 days, purged by ReplyTraceStore. Rollback: docs/reply-traces-rollback.sql (DROP TABLE IF EXISTS chat.reply_traces); nothing else depends on it.
CREATE TABLE IF NOT EXISTS chat.reply_traces (
    trace_id        UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID,
    channel         VARCHAR(12),
    source          VARCHAR(8) NOT NULL DEFAULT 'REPLY',
    started_at      TIMESTAMPTZ NOT NULL,
    reply_ms        INTEGER,
    end_to_end_ms   INTEGER,
    outcome         VARCHAR(10) NOT NULL,
    model           VARCHAR(12),
    model_calls     SMALLINT,
    summary         JSONB NOT NULL,
    events          JSONB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reply_traces_tenant_time ON chat.reply_traces (tenant_id, started_at);
CREATE INDEX IF NOT EXISTS idx_reply_traces_time ON chat.reply_traces (started_at);

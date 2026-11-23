-- Rolls back V115__deferred_replies.sql (run by hand; Flyway does not undo). Loses only the queue of deferred customer turns (a turn still waiting for its retry would then never be retried);
-- messages, conversations and the needs-human flags are untouched. Switch the feature off first with AI_DEFERRED=false and restart, so no code writes to the table meanwhile.
DROP TABLE IF EXISTS chat.deferred_replies;
-- then: DELETE FROM flyway_schema_history WHERE version = '115';

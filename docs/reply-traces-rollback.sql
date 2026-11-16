-- Rollback of V96__reply_traces.sql (run by hand; Flyway migrations here are forward-only). Nothing else references this table.
DROP TABLE IF EXISTS chat.reply_traces;
-- and, only if V96 was recorded by Flyway and must be re-runnable:
-- DELETE FROM flyway_schema_history WHERE version = '96';

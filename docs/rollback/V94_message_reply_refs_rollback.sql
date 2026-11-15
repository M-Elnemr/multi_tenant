-- Rolls back V94__message_reply_refs.sql (run by hand; Flyway does not undo). Loses only the per-line reply references of bursts; messages are untouched.
ALTER TABLE chat.messages DROP COLUMN IF EXISTS reply_refs;
-- then: DELETE FROM flyway_schema_history WHERE version = '94';

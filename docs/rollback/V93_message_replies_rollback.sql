-- Rolls back V93__message_replies.sql (run manually; Flyway does not undo migrations). Loses reply links and platform message ids only; messages are untouched.
DROP TABLE IF EXISTS chat.message_mids;
DROP INDEX IF EXISTS chat.idx_messages_reply_to;
ALTER TABLE chat.messages DROP COLUMN IF EXISTS reply_quote, DROP COLUMN IF EXISTS reply_to_mid, DROP COLUMN IF EXISTS reply_to_id;
-- then: DELETE FROM flyway_schema_history WHERE version = '93';  (so the migration can be applied again)

-- Customers' photos and voice notes read by the AI: which kind each usage row is, and a voice note's length (speech recognition is billed by the second,
-- and the US workspace reports no tokens for it), so the admin sees their cost per shop and overall.
ALTER TABLE chat.ai_usage_log
    ADD COLUMN media_kind VARCHAR(8),
    ADD COLUMN media_seconds INTEGER;
CREATE INDEX IF NOT EXISTS ai_usage_log_media_idx ON chat.ai_usage_log (created_at) WHERE media_kind IS NOT NULL;

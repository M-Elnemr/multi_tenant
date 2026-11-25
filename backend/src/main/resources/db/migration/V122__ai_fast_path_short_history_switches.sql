-- The platform admin's switches to test two AI steps against the current behaviour (both off = NULL until switched on):
-- «fast path»: no model call that cannot change the reply and no lookup for a product already in front of the model;
-- «short history»: the model reads only the recent messages, the earlier chat comes from the database.
ALTER TABLE chat.platform_ai_config ADD COLUMN IF NOT EXISTS fast_path VARCHAR(3) CHECK (fast_path IS NULL OR fast_path IN ('on', 'off'));
ALTER TABLE chat.platform_ai_config ADD COLUMN IF NOT EXISTS short_history VARCHAR(3) CHECK (short_history IS NULL OR short_history IN ('on', 'off'));

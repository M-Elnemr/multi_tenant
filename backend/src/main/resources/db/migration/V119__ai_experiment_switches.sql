-- The platform admin's switches to test the next AI steps against the current behaviour: «fewer rewrites» (phase 2: code puts known problems right instead of a second model
-- call) and «prompt diet» (phase 3: only the rule sections this chat needs, a compact catalog). NULL or 'off' = the current behaviour. Backward compatible: two nullable columns.
ALTER TABLE chat.platform_ai_config ADD COLUMN IF NOT EXISTS fewer_rewrites VARCHAR(3) CHECK (fewer_rewrites IS NULL OR fewer_rewrites IN ('on', 'off'));
ALTER TABLE chat.platform_ai_config ADD COLUMN IF NOT EXISTS prompt_diet VARCHAR(3) CHECK (prompt_diet IS NULL OR prompt_diet IN ('on', 'off'));

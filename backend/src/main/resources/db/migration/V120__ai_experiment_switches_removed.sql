-- Decided by the 2026-11-25 A/B: «fewer rewrites» (code settles known problems without a second model call) is always on, and the «prompt diet» experiment is removed.
-- Neither needs an admin switch any more.
ALTER TABLE chat.platform_ai_config DROP COLUMN IF EXISTS fewer_rewrites;
ALTER TABLE chat.platform_ai_config DROP COLUMN IF EXISTS prompt_diet;

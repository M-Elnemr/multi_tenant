-- chat.platform_ai_config.optimization belonged to the AI optimization switch removed on 2026-11-24 (the optimized request layout is permanent); nothing reads it any more.
ALTER TABLE chat.platform_ai_config DROP COLUMN IF EXISTS optimization;

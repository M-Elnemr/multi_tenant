-- Decided by the 2026-11-25 A/B: «fewer AI steps» is always on and «short history» is removed (same quality, no saving in real chats); neither needs an admin switch.
ALTER TABLE chat.platform_ai_config DROP COLUMN IF EXISTS fast_path;
ALTER TABLE chat.platform_ai_config DROP COLUMN IF EXISTS short_history;

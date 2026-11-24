-- The platform admin's switch for the optimized AI request layout (cache-friendly prompt order, live stock outside the cached part, fewer extra model calls).
-- NULL means the default (off: the classic layout), so turning it back is setting it to NULL or 'off'. Backward compatible: one nullable column, nothing else changes.
ALTER TABLE chat.platform_ai_config ADD COLUMN IF NOT EXISTS optimization VARCHAR(3) CHECK (optimization IS NULL OR optimization IN ('on', 'off'));

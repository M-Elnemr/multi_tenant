-- A burst of customer lines is one stored message; each line keeps its OWN reply reference here, in order:
--   reply_refs  [{line, id, mid, sender, kind, text (max 600), hasMedia, at, source}]   (the first one is also in reply_to_id / reply_to_mid / reply_quote)
-- Backward compatible: one nullable column; no row changes.
ALTER TABLE chat.messages ADD COLUMN IF NOT EXISTS reply_refs JSONB;

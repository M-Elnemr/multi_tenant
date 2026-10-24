-- Broadcast messages can carry several wordings and details of each customer's previous order; each recipient gets their own text.
ALTER TABLE commerce.broadcasts ADD COLUMN variants JSONB;
ALTER TABLE commerce.broadcast_recipients ADD COLUMN vars JSONB;

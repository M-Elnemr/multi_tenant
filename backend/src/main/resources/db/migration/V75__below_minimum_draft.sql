-- The order the customer was told is below the shop's minimum (items and details as sent to prepare_order), so a change to it («غيرها لـ كبيرة») is applied by code
-- and the customer hears the new total. Never confirmable: only chat.conversations.pending is.
ALTER TABLE chat.conversations ADD COLUMN below_minimum JSONB;

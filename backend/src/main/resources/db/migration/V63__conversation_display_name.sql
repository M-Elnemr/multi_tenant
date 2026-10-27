-- The name the customer's chat app shows for them (WhatsApp profile name), cleaned to a first name: the assistant greets them as أستاذ/أستاذة + name.
ALTER TABLE chat.conversations ADD COLUMN display_name VARCHAR(40);

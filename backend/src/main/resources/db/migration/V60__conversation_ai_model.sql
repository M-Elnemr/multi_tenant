-- The model a customer chose for this chat with a command ("claude", "gemini 3.1", "gemini 2.5"); null = the channel's default.
ALTER TABLE chat.conversations ADD COLUMN ai_model VARCHAR(12);

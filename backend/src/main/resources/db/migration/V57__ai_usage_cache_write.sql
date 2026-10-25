-- Tokens written to the provider's prompt cache (billed higher than a normal input token by some providers); they are already counted inside input_tokens.
ALTER TABLE chat.ai_usage_log ADD COLUMN IF NOT EXISTS cache_write_tokens INTEGER NOT NULL DEFAULT 0;

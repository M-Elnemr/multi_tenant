-- Which language model answered ("gemini", "anthropic"...) and whether a reply fell back to the default model, so two models can be compared on real traffic. Old rows have no model: they count as the default one.
ALTER TABLE chat.ai_usage_log ADD COLUMN IF NOT EXISTS model VARCHAR(16);
ALTER TABLE chat.ai_usage_log ADD COLUMN IF NOT EXISTS fallback BOOLEAN NOT NULL DEFAULT FALSE;

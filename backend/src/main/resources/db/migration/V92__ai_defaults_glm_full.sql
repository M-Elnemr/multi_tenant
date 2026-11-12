-- 2026-11-12: the platform's AI defaults are GLM 5.3 Flash for chat, the full prompt, and low reasoning. Earlier admin choices of the text model, the prompt style and DeepSeek's
-- reasoning are cleared (NULL = the default); the photo and voice-note models keep their choices.
UPDATE chat.platform_ai_config SET text_model = NULL, prompt_mode = NULL, deepseek_effort = NULL, updated_at = now() WHERE id = 1;

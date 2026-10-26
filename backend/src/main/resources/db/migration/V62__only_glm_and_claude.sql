-- The platform keeps two language models, GLM (default) and Claude Haiku. Earlier rows with no model were answered by the old default, Gemini 3.1: name it, so their cost stays right
-- while rows written from now on count as GLM. A chat that had picked a removed model goes back to the default.
UPDATE chat.ai_usage_log SET model = 'gemini' WHERE model IS NULL;
UPDATE chat.conversations SET ai_model = NULL WHERE ai_model IN ('gemini', 'gemini25', 'deepseek');

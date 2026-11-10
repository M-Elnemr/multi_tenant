-- The shop's choice of the AI assistant's picture (one of ten built-in avatars: men, girls, women, robots), shown in the storefront chat. NULL = the default (Nada, a woman).
ALTER TABLE chat.settings ADD COLUMN IF NOT EXISTS agent_avatar VARCHAR(12);

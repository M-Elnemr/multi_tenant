-- The platform admin's AI settings: which model answers text, reads images and writes out voice notes, how the prompt is written, and the Hive keys the requests are spread over.
-- A NULL setting means "use the default" (the default is in the code and is shown with a "Default" tag in the admin screen), so a reset is just setting it back to NULL.
CREATE TABLE chat.platform_ai_config (
    id              SMALLINT PRIMARY KEY CHECK (id = 1),
    text_model      VARCHAR(12),
    image_model     VARCHAR(12),
    audio_model     VARCHAR(12),
    prompt_mode     VARCHAR(8) CHECK (prompt_mode IS NULL OR prompt_mode IN ('lean', 'full')),
    deepseek_effort VARCHAR(8) CHECK (deepseek_effort IS NULL OR deepseek_effort IN ('none', 'low', 'medium', 'high')),
    updated_by      UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hive keys added by the admin (encrypted with TokenCrypto). The keys of the server environment are used as well and are not stored here.
CREATE TABLE chat.platform_ai_keys (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider   VARCHAR(12) NOT NULL DEFAULT 'hive' CHECK (provider = 'hive'),
    label      VARCHAR(60) NOT NULL,
    key_enc    TEXT NOT NULL,
    last4      VARCHAR(4) NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

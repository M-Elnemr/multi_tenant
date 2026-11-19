-- Telegram Business, TikTok, X and Snapchat messaging join Facebook / Instagram / WhatsApp as places the AI assistant answers customers.
ALTER TABLE chat.channels DROP CONSTRAINT IF EXISTS channels_platform_check;
ALTER TABLE chat.channels ADD CONSTRAINT channels_platform_check CHECK (platform IN ('FACEBOOK', 'INSTAGRAM', 'WHATSAPP', 'TELEGRAM', 'TIKTOK', 'X', 'SNAPCHAT'));
ALTER TABLE chat.conversations DROP CONSTRAINT IF EXISTS conversations_channel_check;
ALTER TABLE chat.conversations ADD CONSTRAINT conversations_channel_check CHECK (channel IN ('WEB', 'TEST', 'FACEBOOK', 'INSTAGRAM', 'WHATSAPP', 'TELEGRAM', 'TIKTOK', 'X', 'SNAPCHAT'));

-- who the connected business account belongs to on that network (for Telegram: the owner's user id, whose own messages in a customer chat are not the customer's)
ALTER TABLE chat.channels ADD COLUMN IF NOT EXISTS owner_ref VARCHAR(60);

-- "Connect Telegram": the owner opens a link that carries a one-time code, the bot learns which shop that Telegram user is the owner of, and the owner's Business connection is then matched to the shop.
CREATE TABLE chat.social_links (
    code       VARCHAR(40) PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL,
    platform   VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE chat.social_owners (
    platform    VARCHAR(12) NOT NULL,
    external_id VARCHAR(60) NOT NULL,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (platform, external_id)
);

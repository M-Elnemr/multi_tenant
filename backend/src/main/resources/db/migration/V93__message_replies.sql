-- Replies to a specific message (WhatsApp-style), on every channel. Backward compatible: only adds nullable columns, one index and one table; no existing row changes.
--   reply_to_id   the message (of the same chat) this one answers; SET NULL if that message is ever deleted (the snapshot below stays)
--   reply_to_mid  the platform's id of the message replied to, kept even when we hold no copy of it
--   reply_quote   what the quoted message said when the reply was written: {sender, kind, text (max 600 chars), hasMedia, at, source}
ALTER TABLE chat.messages
    ADD COLUMN IF NOT EXISTS reply_to_id  BIGINT REFERENCES chat.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS reply_to_mid VARCHAR(200),
    ADD COLUMN IF NOT EXISTS reply_quote  JSONB;
CREATE INDEX IF NOT EXISTS idx_messages_reply_to ON chat.messages (reply_to_id) WHERE reply_to_id IS NOT NULL;

-- The platform's own ids of the messages of a chat (WhatsApp wamid, Messenger/Instagram mid, Evolution key id), so a customer's native «reply» (which carries only that id)
-- finds the message it quotes. One of our messages can be several bubbles (part_text is the exact bubble); a burst of customer lines is one row with several ids.
CREATE TABLE IF NOT EXISTS chat.message_mids (
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    provider_mid    VARCHAR(200) NOT NULL,
    message_id      BIGINT NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    part_text       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, provider_mid)
);
CREATE INDEX IF NOT EXISTS idx_message_mids_message ON chat.message_mids (message_id);

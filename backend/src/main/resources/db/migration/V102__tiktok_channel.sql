-- A connected account can be authorized yet unable to message (approval missing, region, expired login): say which, in words the shop sees.
ALTER TABLE chat.channels
    ADD COLUMN connection_state VARCHAR(20) NOT NULL DEFAULT 'CONNECTED' CHECK (connection_state IN ('CONNECTED', 'APPROVAL_REQUIRED', 'RESTRICTED', 'REAUTH_REQUIRED', 'UNAVAILABLE', 'ERROR')),
    ADD COLUMN status_note      VARCHAR(300);

-- Every message we send on a network with messaging limits (TikTok: 10 in 48 h, then unlimited after the customer replies, then 3 more after 48 h of silence).
CREATE TABLE chat.social_sends (
    id               BIGSERIAL PRIMARY KEY,
    platform         VARCHAR(12) NOT NULL,
    account_id       VARCHAR(80) NOT NULL,
    conversation_ref VARCHAR(200) NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_social_sends_conv ON chat.social_sends (platform, account_id, conversation_ref, sent_at DESC);

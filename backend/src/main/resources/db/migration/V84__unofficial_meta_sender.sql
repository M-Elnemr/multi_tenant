-- Hand-style ("manual style") sending to OLD Messenger / Instagram contacts, outside Meta's 24 h window, through an UNOFFICIAL route.
-- Off by default everywhere (app.meta.unofficial.enabled=false) and explicitly accepted per shop. Verified only against fakes: never a real Instagram account.
CREATE TABLE chat.unofficial_sessions (
    tenant_id        UUID        NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform         VARCHAR(12) NOT NULL CHECK (platform IN ('FACEBOOK', 'INSTAGRAM')),
    account_name     VARCHAR(120),
    credentials_enc  TEXT,                                 -- AES-GCM (chat.TokenCrypto) of {username, password, totpSecret}; never logged, never returned by any API
    status           VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'NEEDS_LOGIN', 'BLOCKED', 'DISABLED')),
    risk_accepted_at TIMESTAMPTZ,
    risk_accepted_by UUID,
    warmup_started_at TIMESTAMPTZ,
    sent_today       INT         NOT NULL DEFAULT 0,
    sent_today_date  DATE,
    last_sent_at     TIMESTAMPTZ,
    blocked_until    TIMESTAMPTZ,
    last_error       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, platform)
);

ALTER TABLE commerce.broadcast_recipients
    ADD COLUMN route VARCHAR(12) NOT NULL DEFAULT 'OFFICIAL' CHECK (route IN ('OFFICIAL', 'MANUAL_STYLE'));
CREATE INDEX broadcast_recipients_manual_idx ON commerce.broadcast_recipients (channel, external_id) WHERE route = 'MANUAL_STYLE';

-- pause reasons got longer (ACCOUNT_BLOCK_SIGNAL, WHATSAPP_DISCONNECTED, ...)
ALTER TABLE commerce.broadcasts ALTER COLUMN pause_reason TYPE VARCHAR(32);

-- pacing state of the two lanes of a broadcast: the official lane (5-15 s) and the manual-style lane (40-120 s)
ALTER TABLE commerce.broadcasts
    ADD COLUMN manual_next_at     TIMESTAMPTZ,
    ADD COLUMN manual_seq         INT NOT NULL DEFAULT 0,
    ADD COLUMN manual_since_break INT NOT NULL DEFAULT 0,
    ADD COLUMN manual_break_after INT,
    ADD COLUMN manual_last_hash   VARCHAR(64),
    ADD COLUMN wa_seq             INT NOT NULL DEFAULT 0,
    ADD COLUMN wa_since_break     INT NOT NULL DEFAULT 0,
    ADD COLUMN wa_break_after     INT,
    ADD COLUMN wa_last_hash       VARCHAR(64);

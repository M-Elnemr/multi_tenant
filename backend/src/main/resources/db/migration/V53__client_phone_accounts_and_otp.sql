-- Shop customers can have an account with a phone number and password (made for them after they confirm an order on WhatsApp, or by registering with a WhatsApp code).
ALTER TABLE commerce.client_accounts ALTER COLUMN google_sub DROP NOT NULL;
ALTER TABLE commerce.client_accounts
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN phone_verified_at TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN created_via VARCHAR(10) NOT NULL DEFAULT 'GOOGLE' CHECK (created_via IN ('GOOGLE', 'WHATSAPP', 'REGISTER'));
-- one account per phone number: an older duplicate keeps its login but loses the shared number
UPDATE commerce.client_accounts a SET phone = NULL
WHERE phone IS NOT NULL AND EXISTS (SELECT 1 FROM commerce.client_accounts b WHERE b.phone = a.phone AND (b.created_at, b.id) < (a.created_at, a.id));
CREATE UNIQUE INDEX uq_client_phone ON commerce.client_accounts (phone) WHERE phone IS NOT NULL;

-- Whether we already told the customer about their account for this order (once per order, never twice).
ALTER TABLE commerce.orders ADD COLUMN account_notified_at TIMESTAMPTZ;

-- One-time codes sent on WhatsApp from the platform's own number.
CREATE TABLE core.phone_otps (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone      VARCHAR(20) NOT NULL,
    purpose    VARCHAR(10) NOT NULL CHECK (purpose IN ('REGISTER', 'RESET')),
    code_hash  VARCHAR(64) NOT NULL,
    payload    JSONB,
    attempts   INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_phone_otps_phone ON core.phone_otps (phone, created_at DESC);

-- The platform's own WhatsApp number (linked by the platform admin with a QR code) used to send those codes.
CREATE TABLE chat.platform_whatsapp (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    instance   VARCHAR(60) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'DISCONNECTED' CHECK (status IN ('DISCONNECTED', 'CONNECTING', 'CONNECTED')),
    phone      VARCHAR(30),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

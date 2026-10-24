-- Free (the shop's own linked number) or paid (official WhatsApp, charged from the shop's prepaid credit) per WhatsApp service, and richer broadcasts.
ALTER TABLE chat.wa_settings ADD COLUMN send_mode VARCHAR(8) NOT NULL DEFAULT 'PAID' CHECK (send_mode IN ('PAID', 'FREE'));
ALTER TABLE commerce.cart_settings ADD COLUMN send_mode VARCHAR(8) NOT NULL DEFAULT 'PAID' CHECK (send_mode IN ('PAID', 'FREE'));

ALTER TABLE commerce.broadcasts
    ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'PAID' CHECK (mode IN ('NONE', 'FREE', 'PAID')),
    ADD COLUMN push BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN image_file_id UUID,
    ADD COLUMN push_sent INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pending_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN charged_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DONE' CHECK (status IN ('SENDING', 'DONE'));
ALTER TABLE commerce.broadcasts ALTER COLUMN message TYPE VARCHAR(1000);
ALTER TABLE commerce.broadcasts ALTER COLUMN segment TYPE VARCHAR(12);

-- Messages waiting to go out. Free ones are paced (a number that sends too fast gets banned); paid ones are charged one by one as they are sent.
CREATE TABLE commerce.broadcast_recipients (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id UUID NOT NULL REFERENCES commerce.broadcasts(id) ON DELETE CASCADE,
    phone        VARCHAR(30) NOT NULL,
    name         VARCHAR(150),
    status       VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    error        VARCHAR(200),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX idx_broadcast_recipients_pending ON commerce.broadcast_recipients (broadcast_id) WHERE status = 'PENDING';

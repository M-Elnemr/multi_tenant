CREATE SCHEMA IF NOT EXISTS notifications;

CREATE TABLE notifications.notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES core.users(id),
    tenant_id UUID REFERENCES core.tenants(id),
    notification_type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    payload JSONB,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications.notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications.notifications (user_id) WHERE read_at IS NULL;

-- Outbound messages are written in the same breath as the event and sent by a background job, so a slow or
-- failing mail server can never block checkout or booking (spec 30).
CREATE TABLE notifications.outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID, tenant_id UUID,
    channel VARCHAR(12) NOT NULL CHECK (channel IN ('EMAIL','SMS','WHATSAPP','PUSH')),
    to_address VARCHAR(254) NOT NULL, subject VARCHAR(200) NOT NULL, body TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts INT NOT NULL DEFAULT 0, last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON notifications.outbox (created_at) WHERE status = 'PENDING';

ALTER TABLE medical.appointments ADD COLUMN reminder_sent_at TIMESTAMPTZ;

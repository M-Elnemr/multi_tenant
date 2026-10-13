-- Shop plan: 500 EGP / month (clinic plan unchanged).
UPDATE billing.subscription_plans SET price_minor = 50000 WHERE code = 'STORE_PRO';

-- Replays of a mutating clinic request (phone was offline, the answer got lost) return the stored answer instead of doing the work twice.
CREATE TABLE core.request_replays (
    tenant_id  UUID         NOT NULL,
    idem_key   VARCHAR(100) NOT NULL,
    status     INT          NOT NULL,
    body       TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idem_key)
);
CREATE INDEX idx_request_replays_created ON core.request_replays (created_at);

-- A walk-in who is removed from the queue is cancelled; a booked patient goes back to "reserved".
ALTER TABLE medical.appointments ADD COLUMN is_walk_in BOOLEAN NOT NULL DEFAULT FALSE;

-- Owner-chosen order / visibility of the sections on the public site, and the shop's editable trust tiles.
ALTER TABLE medical.clinic_profiles ADD COLUMN site_layout JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE commerce.store_profiles ADD COLUMN site_layout JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE commerce.store_profiles ADD COLUMN trust_tiles JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Lets a phone that lost the answer of a refresh retry with the old token (only rotation, never logout or a password change, qualifies).
ALTER TABLE core.user_sessions ADD COLUMN rotated_at TIMESTAMPTZ;

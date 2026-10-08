-- One login across the platform host and a clinic/shop host: a short-lived, single-use ticket (only its hash is stored).
CREATE TABLE core.sso_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash CHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES core.users(id),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sso_tickets_expiry ON core.sso_tickets (expires_at);

-- Patients are entered by age (years + months); the stored date of birth is then an estimate.
ALTER TABLE medical.patients ADD COLUMN dob_estimated BOOLEAN NOT NULL DEFAULT FALSE;

-- A prescription can be a photo/scan of the doctor's own paper prescription instead of (or next to) typed items.
ALTER TABLE medical.prescriptions ADD COLUMN image_file_id UUID;

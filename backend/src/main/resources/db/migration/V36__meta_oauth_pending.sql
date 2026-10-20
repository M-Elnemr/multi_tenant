-- Pages / Instagram / WhatsApp numbers found after "Connect with Facebook", held encrypted until the owner chooses (purged after 30 minutes).
CREATE TABLE chat.oauth_pending (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL,
    payload_enc TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

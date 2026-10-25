-- Two-step sign-in (an authenticator app) for staff accounts: owners and platform admins can switch it on. The secret is encrypted; recovery codes are stored hashed.
CREATE TABLE core.user_totp (
    user_id         UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    secret_enc      TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    last_step       BIGINT NOT NULL DEFAULT 0,
    recovery_hashes TEXT[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    enabled_at      TIMESTAMPTZ
);

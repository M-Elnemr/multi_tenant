CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS audit;

-- Tenants -----------------------------------------------------------------
CREATE TABLE core.tenants (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug             VARCHAR(63)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    legal_name       VARCHAR(200),
    tenant_type      VARCHAR(30)  NOT NULL CHECK (tenant_type IN ('STORE','CLINIC')),
    status           VARCHAR(30)  NOT NULL CHECK (status IN ('PENDING','TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED','ARCHIVED')),
    default_locale   VARCHAR(10)  NOT NULL DEFAULT 'ar',
    default_currency VARCHAR(3)   NOT NULL DEFAULT 'EGP',
    timezone         VARCHAR(60)  NOT NULL DEFAULT 'Africa/Cairo',
    logo_file_id     UUID,
    trial_ends_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE core.tenant_domains (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES core.tenants(id),
    host               VARCHAR(253) NOT NULL UNIQUE CHECK (host = lower(host)),
    kind               VARCHAR(20)  NOT NULL CHECK (kind IN ('SUBDOMAIN','CUSTOM')),
    is_primary         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    verification_token VARCHAR(100),
    verified_at        TIMESTAMPTZ,
    ssl_status         VARCHAR(30),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_domains_tenant ON core.tenant_domains (tenant_id);
-- one primary domain per tenant
CREATE UNIQUE INDEX uq_tenant_primary_domain ON core.tenant_domains (tenant_id) WHERE is_primary;

CREATE TABLE core.tenant_settings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL UNIQUE REFERENCES core.tenants(id),
    settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE core.branding (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL UNIQUE REFERENCES core.tenants(id),
    primary_color      VARCHAR(9)  NOT NULL DEFAULT '#0F766E',
    secondary_color    VARCHAR(9)  NOT NULL DEFAULT '#F59E0B',
    logo_file_id       UUID,
    favicon_file_id    UUID,
    font_family        VARCHAR(100),
    custom_css_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Users (global identity) -------------------------------------------------
CREATE TABLE core.users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email             VARCHAR(254),
    phone             VARCHAR(20),
    password_hash     VARCHAR(255),
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL DEFAULT '',
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('INVITED','ACTIVE','DISABLED')),
    avatar_file_id    UUID,
    email_verified_at TIMESTAMPTZ,
    phone_verified_at TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_identifier_present CHECK (email IS NOT NULL OR phone IS NOT NULL)
);
CREATE UNIQUE INDEX uq_users_email ON core.users (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_phone ON core.users (phone) WHERE phone IS NOT NULL;

CREATE TABLE core.user_tenant_memberships (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, tenant_id)
);
CREATE INDEX idx_memberships_tenant ON core.user_tenant_memberships (tenant_id);

-- RBAC ----------------------------------------------------------------------
CREATE TABLE core.roles (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES core.tenants(id),   -- NULL = predefined role
    code      VARCHAR(60)  NOT NULL,
    name      VARCHAR(120) NOT NULL,
    scope     VARCHAR(10)  NOT NULL CHECK (scope IN ('PLATFORM','TENANT'))
);
CREATE UNIQUE INDEX uq_roles_code ON core.roles (code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_roles_tenant_code ON core.roles (tenant_id, code) WHERE tenant_id IS NOT NULL;

CREATE TABLE core.permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(80) NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    description TEXT
);

CREATE TABLE core.role_permissions (
    role_id       UUID NOT NULL REFERENCES core.roles(id),
    permission_id UUID NOT NULL REFERENCES core.permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE core.membership_roles (
    membership_id UUID NOT NULL REFERENCES core.user_tenant_memberships(id),
    role_id       UUID NOT NULL REFERENCES core.roles(id),
    PRIMARY KEY (membership_id, role_id)
);

-- Platform-level roles for users (PLATFORM_ADMIN etc.), no tenant.
CREATE TABLE core.user_platform_roles (
    user_id UUID NOT NULL REFERENCES core.users(id),
    role_id UUID NOT NULL REFERENCES core.roles(id),
    PRIMARY KEY (user_id, role_id)
);

-- Sessions / activation -----------------------------------------------------
CREATE TABLE core.user_sessions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES core.users(id),
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    revoked_at         TIMESTAMPTZ,
    replaced_by        UUID,
    ip_address         VARCHAR(45),
    user_agent         TEXT
);
CREATE INDEX idx_sessions_user ON core.user_sessions (user_id);

-- One-time activation PIN given by the clinic/store so a user can create their first
-- password without a paid SMS/OTP.
CREATE TABLE core.activation_pins (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id),
    tenant_id  UUID REFERENCES core.tenants(id),
    pin_hash   VARCHAR(255) NOT NULL,
    attempts   INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_activation_pins_user ON core.activation_pins (user_id);

-- Idempotency for onboarding retries -----------------------------------------
CREATE TABLE core.idempotency_keys (
    key         VARCHAR(100) PRIMARY KEY,
    scope       VARCHAR(60) NOT NULL,
    result_id   UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit -----------------------------------------------------------------------
CREATE TABLE audit.audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id UUID,
    tenant_id     UUID,
    action        VARCHAR(80) NOT NULL,
    entity_type   VARCHAR(80) NOT NULL,
    entity_id     UUID,
    request_id    VARCHAR(64),
    ip_address    VARCHAR(45),
    user_agent    TEXT,
    metadata_json JSONB
);
CREATE INDEX idx_audit_tenant_time ON audit.audit_logs (tenant_id, occurred_at DESC);

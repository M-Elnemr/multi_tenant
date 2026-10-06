CREATE TABLE core.files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    owner_user_id UUID NOT NULL REFERENCES core.users(id),
    object_key VARCHAR(300) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    declared_size BIGINT NOT NULL CHECK (declared_size > 0),
    file_size BIGINT,
    checksum VARCHAR(64),
    category VARCHAR(30) NOT NULL CHECK (category IN ('PRODUCT_IMAGE','LOGO','AVATAR','LAB_RESULT','MEDICAL_DOCUMENT','PRESCRIPTION')),
    visibility VARCHAR(20) NOT NULL CHECK (visibility IN ('PRIVATE','TENANT_INTERNAL','CUSTOMER_VISIBLE','PATIENT_VISIBLE','PUBLIC')),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','READY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at TIMESTAMPTZ
);
CREATE INDEX idx_files_tenant ON core.files (tenant_id, created_at DESC);
CREATE INDEX idx_files_pending ON core.files (created_at) WHERE status = 'PENDING';

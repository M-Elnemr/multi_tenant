-- Services v2: categories, images, promotion price, location, per-service capacity, add-ons, the customer's answers, special days, pending expiry, cancel deadline.
CREATE TABLE commerce.service_categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name       VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, name)
);

ALTER TABLE commerce.services
    ADD COLUMN category_id        UUID REFERENCES commerce.service_categories(id) ON DELETE SET NULL,
    ADD COLUMN short_description  VARCHAR(240),
    ADD COLUMN price_before_minor BIGINT CHECK (price_before_minor IS NULL OR price_before_minor >= 0),
    ADD COLUMN max_concurrent     INT CHECK (max_concurrent IS NULL OR max_concurrent BETWEEN 1 AND 50),   -- at most this many of THIS service at the same time (null: only the business capacity applies)
    ADD COLUMN location_mode      VARCHAR(8) NOT NULL DEFAULT 'SHOP' CHECK (location_mode IN ('SHOP', 'HOME', 'BOTH')),
    ADD COLUMN service_area       TEXT,
    ADD COLUMN instructions       TEXT,
    ADD COLUMN cancellation_policy TEXT,
    ADD COLUMN image_file_id      UUID,
    ADD COLUMN gallery            UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN required_fields    JSONB NOT NULL DEFAULT '[]';   -- [{key, label, required}]: what this service needs from the customer (appliance and problem, filter type, address...)

CREATE TABLE commerce.service_addons (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    service_id       UUID NOT NULL REFERENCES commerce.services(id) ON DELETE CASCADE,
    name             VARCHAR(120) NOT NULL,
    price_minor      BIGINT NOT NULL DEFAULT 0 CHECK (price_minor >= 0),
    duration_minutes INT NOT NULL DEFAULT 0 CHECK (duration_minutes BETWEEN 0 AND 480),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order       INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_service_addons_service ON commerce.service_addons (service_id, is_active, sort_order);

-- A date with its own hours (a short Friday, Ramadan hours): replaces that weekday's hours on that date. A day off stays a closure.
CREATE TABLE commerce.service_special_days (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    day        DATE NOT NULL,
    opens_at   TIME NOT NULL,
    closes_at  TIME NOT NULL CHECK (closes_at > opens_at)
);
CREATE INDEX idx_service_special_days ON commerce.service_special_days (tenant_id, day);

ALTER TABLE commerce.booking_settings
    ADD COLUMN pending_expires_minutes INT CHECK (pending_expires_minutes IS NULL OR pending_expires_minutes BETWEEN 5 AND 10080),   -- a request nobody answered stops holding the time (null: never)
    ADD COLUMN cancel_deadline_hours   INT NOT NULL DEFAULT 0 CHECK (cancel_deadline_hours BETWEEN 0 AND 720);                        -- a customer cannot cancel or move an appointment closer than this

ALTER TABLE commerce.bookings
    ADD COLUMN answers       JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN addons        JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN location_mode VARCHAR(8),
    ADD COLUMN address       TEXT;

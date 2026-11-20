-- What the shop wants the AI assistant to do in a chat: take product orders (the default, as before), collect the customer as a lead, take appointments for services.
ALTER TABLE chat.settings ADD COLUMN goals VARCHAR(12)[] NOT NULL DEFAULT '{ORDERS}';

-- Where a customer record came from (the dashboard can then show «leads»: people who asked but have not bought).
ALTER TABLE commerce.customers ADD COLUMN source VARCHAR(12) NOT NULL DEFAULT 'ORDER';

-- Services a shop sells by appointment (a haircut, make-up, a massage): duration, price, and the shop's own opening hours.
CREATE TABLE commerce.services (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name              VARCHAR(160) NOT NULL,
    description       TEXT,
    duration_minutes  INT NOT NULL CHECK (duration_minutes BETWEEN 5 AND 720),
    buffer_minutes    INT NOT NULL DEFAULT 0 CHECK (buffer_minutes BETWEEN 0 AND 240),
    price_minor       BIGINT NOT NULL DEFAULT 0 CHECK (price_minor >= 0),
    currency          VARCHAR(3) NOT NULL DEFAULT 'EGP',
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order        INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_services_tenant ON commerce.services (tenant_id, is_active, sort_order);

CREATE TABLE commerce.booking_settings (
    tenant_id             UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    stations              INT NOT NULL DEFAULT 1 CHECK (stations BETWEEN 1 AND 50),          -- how many customers can be served at the same time (chairs, rooms, staff)
    slot_step_minutes     INT NOT NULL DEFAULT 30 CHECK (slot_step_minutes BETWEEN 5 AND 240),
    min_notice_minutes    INT NOT NULL DEFAULT 120 CHECK (min_notice_minutes BETWEEN 0 AND 10080),
    max_days_ahead        INT NOT NULL DEFAULT 30 CHECK (max_days_ahead BETWEEN 1 AND 365),
    auto_confirm          BOOLEAN NOT NULL DEFAULT FALSE,                                     -- false: the shop confirms each request, as asked
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Opening hours: several rows for one weekday make a break (10-14 and 16-21). weekday 1 = Monday ... 7 = Sunday.
CREATE TABLE commerce.service_hours (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    weekday     INT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    opens_at    TIME NOT NULL,
    closes_at   TIME NOT NULL,
    CHECK (closes_at > opens_at)
);
CREATE INDEX idx_service_hours_tenant ON commerce.service_hours (tenant_id, weekday);

CREATE TABLE commerce.service_closures (
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    closed_on   DATE NOT NULL,
    reason      VARCHAR(160),
    PRIMARY KEY (tenant_id, closed_on)
);

CREATE TABLE commerce.bookings (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    booking_number   VARCHAR(16) NOT NULL,
    service_id       UUID NOT NULL REFERENCES commerce.services(id),
    service_name     VARCHAR(160) NOT NULL,                          -- what it was called when booked
    customer_id      UUID REFERENCES commerce.customers(id),
    customer_name    VARCHAR(150) NOT NULL,
    phone            VARCHAR(30) NOT NULL,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    blocks_until     TIMESTAMPTZ NOT NULL,                           -- ends_at plus the service's buffer: the station is busy until then
    price_minor      BIGINT NOT NULL DEFAULT 0,
    currency         VARCHAR(3) NOT NULL DEFAULT 'EGP',
    status           VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    source           VARCHAR(12) NOT NULL DEFAULT 'AI_CHAT' CHECK (source IN ('AI_CHAT', 'DASHBOARD', 'WEBSITE')),
    conversation_id  UUID,
    note             TEXT,
    decline_reason   VARCHAR(300),
    reminder_sent_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, booking_number),
    CHECK (ends_at > starts_at)
);
CREATE INDEX idx_bookings_tenant_time ON commerce.bookings (tenant_id, starts_at);
CREATE INDEX idx_bookings_live ON commerce.bookings (tenant_id, starts_at) WHERE status IN ('PENDING', 'CONFIRMED');
CREATE INDEX idx_bookings_phone ON commerce.bookings (tenant_id, phone);

INSERT INTO core.permissions (code, name) VALUES
 ('booking.read', 'View services and appointments'), ('booking.manage', 'Manage services and confirm or change appointments');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code IN ('booking.read', 'booking.manage')
ON CONFLICT DO NOTHING;

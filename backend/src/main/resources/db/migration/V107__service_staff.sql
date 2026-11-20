-- Optional staff / resources for a service business (stylists, barbers, technicians, cleaning teams). A one-person business never needs it: without staff for a service, only the business capacity applies.
CREATE TABLE commerce.service_staff (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    image_file_id UUID,
    capacity      INT NOT NULL DEFAULT 1 CHECK (capacity BETWEEN 1 AND 10),   -- appointments this person/team can have at the same time (a cleaning team of 3 may do 1; a barber chair 1)
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order    INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_service_staff_tenant ON commerce.service_staff (tenant_id, is_active, sort_order);

-- which services each person does (their skills)
CREATE TABLE commerce.staff_services (
    staff_id   UUID NOT NULL REFERENCES commerce.service_staff(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES commerce.services(id) ON DELETE CASCADE,
    PRIMARY KEY (staff_id, service_id)
);
CREATE INDEX idx_staff_services_service ON commerce.staff_services (service_id);

-- their own working hours (several rows for one weekday = a break). No row at all: they follow the business hours.
CREATE TABLE commerce.staff_hours (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id  UUID NOT NULL REFERENCES commerce.service_staff(id) ON DELETE CASCADE,
    weekday   INT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    opens_at  TIME NOT NULL,
    closes_at TIME NOT NULL CHECK (closes_at > opens_at)
);
CREATE INDEX idx_staff_hours ON commerce.staff_hours (staff_id, weekday);

CREATE TABLE commerce.staff_days_off (
    staff_id UUID NOT NULL REFERENCES commerce.service_staff(id) ON DELETE CASCADE,
    day      DATE NOT NULL,
    reason   VARCHAR(160),
    PRIMARY KEY (staff_id, day)
);

ALTER TABLE commerce.bookings ADD COLUMN staff_id UUID REFERENCES commerce.service_staff(id) ON DELETE SET NULL;
CREATE INDEX idx_bookings_staff ON commerce.bookings (staff_id, starts_at) WHERE staff_id IS NOT NULL;

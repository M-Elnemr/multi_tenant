-- Lawyers / law firms: a third business type with its own website, bookings, schedule, clients, cases, fees and reminders.

-- 1) the type itself
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
           WHERE contype = 'c' AND pg_get_constraintdef(oid) LIKE '%tenant_type%' AND conrelid IN ('core.tenants'::regclass, 'billing.subscription_plans'::regclass)
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
  END LOOP;
END $$;
ALTER TABLE core.tenants ADD CONSTRAINT tenants_tenant_type_check CHECK (tenant_type IN ('STORE','CLINIC','LAWYER'));
ALTER TABLE billing.subscription_plans ADD CONSTRAINT subscription_plans_tenant_type_check CHECK (tenant_type IN ('STORE','CLINIC','LAWYER'));

-- 2) plans: free trial with everything, then Professional
INSERT INTO billing.subscription_plans (code, tenant_type, name, price_minor, interval, is_trial_plan, is_active, sort_order) VALUES
 ('LAWYER_FREE','LAWYER','Free', 0, 'MONTH', TRUE, TRUE, 10),
 ('LAWYER_PRO','LAWYER','Professional', 60000, 'MONTH', FALSE, TRUE, 30),
 ('LAWYER_PRO_YEARLY','LAWYER','Professional (yearly)', 600000, 'YEAR', FALSE, TRUE, 35);
INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT p.id, f.k, f.en, f.lim FROM billing.subscription_plans p JOIN (VALUES
 ('max_staff', TRUE, 10::bigint), ('max_branches', TRUE, 5), ('custom_domain', TRUE, NULL), ('advanced_reports', TRUE, NULL), ('max_cases', TRUE, NULL)
) AS f(k, en, lim) ON TRUE WHERE p.code IN ('LAWYER_FREE','LAWYER_PRO','LAWYER_PRO_YEARLY');

-- 3) permissions and roles
INSERT INTO core.permissions (code, name) VALUES
 ('legal.case.read','View cases'),('legal.case.manage','Manage cases'),
 ('legal.client.read','View clients'),('legal.client.manage','Manage clients'),
 ('legal.booking.read','View booking requests'),('legal.booking.manage','Approve and deny bookings'),
 ('legal.schedule.read','View the schedule'),('legal.schedule.manage','Manage the schedule'),
 ('legal.finance.read','View fees and payments'),('legal.finance.manage','Record fees and payments')
ON CONFLICT (code) DO NOTHING;
INSERT INTO core.roles (code, name, scope) VALUES ('LAWYER_OWNER','Lawyer (owner)','TENANT'),('ASSOCIATE','Associate lawyer','TENANT'),('LEGAL_SECRETARY','Legal secretary','TENANT');

CREATE OR REPLACE FUNCTION pg_temp.grant_perms(role_code TEXT, perm_codes TEXT[]) RETURNS VOID AS $$
    INSERT INTO core.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM core.roles r, core.permissions p
    WHERE r.code = role_code AND r.tenant_id IS NULL AND p.code = ANY(perm_codes) ON CONFLICT DO NOTHING;
$$ LANGUAGE sql;
SELECT pg_temp.grant_perms('LAWYER_OWNER', ARRAY['settings.read','settings.manage','staff.manage','domain.read','domain.manage','billing.read','billing.manage','report.read',
  'legal.case.read','legal.case.manage','legal.client.read','legal.client.manage','legal.booking.read','legal.booking.manage','legal.schedule.read','legal.schedule.manage',
  'legal.finance.read','legal.finance.manage','copilot.use']);
SELECT pg_temp.grant_perms('ASSOCIATE', ARRAY['legal.case.read','legal.case.manage','legal.client.read','legal.client.manage','legal.booking.read','legal.booking.manage','legal.schedule.read','legal.schedule.manage']);
SELECT pg_temp.grant_perms('LEGAL_SECRETARY', ARRAY['legal.client.read','legal.client.manage','legal.booking.read','legal.booking.manage','legal.schedule.read','legal.schedule.manage','legal.case.read']);

-- 4) the legal schema
CREATE SCHEMA IF NOT EXISTS legal;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE legal.profiles (
    tenant_id UUID PRIMARY KEY REFERENCES core.tenants(id),
    display_name VARCHAR(150) NOT NULL,
    title VARCHAR(150) NOT NULL DEFAULT '',            -- «المستشار», «محامٍ بالنقض»
    headline VARCHAR(250) NOT NULL DEFAULT '',
    bio TEXT NOT NULL DEFAULT '',
    photo_url VARCHAR(500), cover_url VARCHAR(500), logo_url VARCHAR(500),
    bar_number VARCHAR(60) NOT NULL DEFAULT '', bar_level VARCHAR(80) NOT NULL DEFAULT '',  -- e.g. «مقبول أمام محكمة النقض»
    years_experience INT NOT NULL DEFAULT 0,
    practice_areas JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{key, title, description, icon}]
    languages JSONB NOT NULL DEFAULT '[]'::jsonb,
    education JSONB NOT NULL DEFAULT '[]'::jsonb,       -- [{title, place, year}]
    achievements JSONB NOT NULL DEFAULT '[]'::jsonb,    -- [{value, label}] e.g. 500+ cases
    testimonials JSONB NOT NULL DEFAULT '[]'::jsonb,    -- [{name, text, rating}]
    faq JSONB NOT NULL DEFAULT '[]'::jsonb,              -- [{q, a}]
    office_address VARCHAR(300) NOT NULL DEFAULT '', office_city VARCHAR(100) NOT NULL DEFAULT '', map_url VARCHAR(600) NOT NULL DEFAULT '',
    phone VARCHAR(30) NOT NULL DEFAULT '', whatsapp VARCHAR(30) NOT NULL DEFAULT '', email VARCHAR(150) NOT NULL DEFAULT '',
    social JSONB NOT NULL DEFAULT '{}'::jsonb,
    template VARCHAR(20) NOT NULL DEFAULT 'classic' CHECK (template IN ('classic','modern','heritage')),
    site_layout JSONB,
    -- booking rules
    slot_minutes INT NOT NULL DEFAULT 30 CHECK (slot_minutes BETWEEN 10 AND 240),
    buffer_minutes INT NOT NULL DEFAULT 0 CHECK (buffer_minutes BETWEEN 0 AND 120),
    min_notice_hours INT NOT NULL DEFAULT 2 CHECK (min_notice_hours BETWEEN 0 AND 336),
    max_days_ahead INT NOT NULL DEFAULT 30 CHECK (max_days_ahead BETWEEN 1 AND 365),
    max_per_day INT, auto_approve BOOLEAN NOT NULL DEFAULT FALSE, booking_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_days_before INT NOT NULL DEFAULT 1 CHECK (reminder_days_before BETWEEN 0 AND 30),
    daily_digest BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE legal.services (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(150) NOT NULL, description VARCHAR(1000) NOT NULL DEFAULT '',
    duration_minutes INT NOT NULL DEFAULT 30 CHECK (duration_minutes BETWEEN 10 AND 480),
    price_minor BIGINT CHECK (price_minor IS NULL OR price_minor >= 0),
    mode VARCHAR(10) NOT NULL DEFAULT 'OFFICE' CHECK (mode IN ('OFFICE','ONLINE','PHONE')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE, sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_services_tenant ON legal.services (tenant_id, sort_order);

-- weekly hours: ISO weekday 1=Mon .. 7=Sun; several windows a day allowed
CREATE TABLE legal.working_hours (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    weekday INT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time TIME NOT NULL, end_time TIME NOT NULL CHECK (end_time > start_time)
);
CREATE INDEX idx_legal_hours_tenant ON legal.working_hours (tenant_id, weekday);

CREATE TABLE legal.time_off (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
    reason VARCHAR(200) NOT NULL DEFAULT ''
);

CREATE TABLE legal.clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(150) NOT NULL, phone VARCHAR(30) NOT NULL DEFAULT '', phone2 VARCHAR(30) NOT NULL DEFAULT '',
    national_id VARCHAR(30) NOT NULL DEFAULT '', email VARCHAR(150) NOT NULL DEFAULT '', address VARCHAR(300) NOT NULL DEFAULT '',
    kind VARCHAR(10) NOT NULL DEFAULT 'PERSON' CHECK (kind IN ('PERSON','COMPANY')),
    notes TEXT NOT NULL DEFAULT '', tags TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_clients_tenant ON legal.clients (tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_legal_clients_phone ON legal.clients (tenant_id, phone) WHERE phone <> '';
CREATE INDEX idx_legal_clients_trgm ON legal.clients USING gin (name gin_trgm_ops);

CREATE TABLE legal.cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    number VARCHAR(30) NOT NULL,                        -- the office's own number, e.g. 2026-0007
    title VARCHAR(250) NOT NULL,
    case_type VARCHAR(30) NOT NULL DEFAULT 'CIVIL',
    court VARCHAR(200) NOT NULL DEFAULT '', circuit VARCHAR(120) NOT NULL DEFAULT '', court_case_no VARCHAR(80) NOT NULL DEFAULT '',
    opponent VARCHAR(200) NOT NULL DEFAULT '', opponent_lawyer VARCHAR(200) NOT NULL DEFAULT '',
    client_role VARCHAR(20) NOT NULL DEFAULT '',        -- plaintiff / defendant / appellant ...
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('UNDER_STUDY','OPEN','CLOSED')),
    outcome VARCHAR(10) NOT NULL DEFAULT 'NONE' CHECK (outcome IN ('NONE','WON','LOST','SETTLED')),
    opened_on DATE NOT NULL DEFAULT CURRENT_DATE, closed_on DATE,
    description TEXT NOT NULL DEFAULT '',
    agreed_fee_minor BIGINT NOT NULL DEFAULT 0 CHECK (agreed_fee_minor >= 0),
    assigned_to UUID REFERENCES core.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, number)
);
CREATE INDEX idx_legal_cases_tenant ON legal.cases (tenant_id, opened_on DESC);
CREATE INDEX idx_legal_cases_trgm ON legal.cases USING gin ((title || ' ' || court || ' ' || opponent || ' ' || court_case_no) gin_trgm_ops);

CREATE TABLE legal.case_clients (
    case_id UUID NOT NULL REFERENCES legal.cases(id) ON DELETE CASCADE,
    client_id UUID NOT NULL REFERENCES legal.clients(id) ON DELETE CASCADE,
    PRIMARY KEY (case_id, client_id)
);
CREATE INDEX idx_legal_case_clients_client ON legal.case_clients (client_id);

-- notes / work done on a case; each may carry a fee
CREATE TABLE legal.case_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, case_id UUID NOT NULL REFERENCES legal.cases(id) ON DELETE CASCADE,
    action_date DATE NOT NULL DEFAULT CURRENT_DATE,
    title VARCHAR(250) NOT NULL, notes TEXT NOT NULL DEFAULT '',
    fee_minor BIGINT NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_actions_case ON legal.case_actions (case_id, action_date DESC);

-- dated steps (hearings, deadlines, filings): they show on the schedule and send reminders
CREATE TABLE legal.case_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, case_id UUID NOT NULL REFERENCES legal.cases(id) ON DELETE CASCADE,
    kind VARCHAR(12) NOT NULL DEFAULT 'HEARING' CHECK (kind IN ('HEARING','DEADLINE','FILING','MEETING','OTHER')),
    starts_at TIMESTAMPTZ NOT NULL, duration_minutes INT NOT NULL DEFAULT 60,
    description VARCHAR(1000) NOT NULL, location VARCHAR(250) NOT NULL DEFAULT '',
    done BOOLEAN NOT NULL DEFAULT FALSE, result VARCHAR(1000) NOT NULL DEFAULT '',
    reminder_sent_at TIMESTAMPTZ, early_reminder_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_steps_tenant ON legal.case_steps (tenant_id, starts_at);

CREATE TABLE legal.payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, case_id UUID NOT NULL REFERENCES legal.cases(id) ON DELETE CASCADE,
    client_id UUID REFERENCES legal.clients(id) ON DELETE SET NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    paid_on DATE NOT NULL DEFAULT CURRENT_DATE, method VARCHAR(20) NOT NULL DEFAULT 'CASH', note VARCHAR(300) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_payments_tenant ON legal.payments (tenant_id, paid_on DESC);

CREATE TABLE legal.case_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, case_id UUID NOT NULL REFERENCES legal.cases(id) ON DELETE CASCADE,
    file_id UUID, url VARCHAR(500) NOT NULL, name VARCHAR(200) NOT NULL, content_type VARCHAR(100) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- the website's 30-minute sessions: guests ask, the lawyer approves or denies
CREATE TABLE legal.bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    code VARCHAR(12) NOT NULL,
    service_id UUID REFERENCES legal.services(id) ON DELETE SET NULL,
    client_id UUID REFERENCES legal.clients(id) ON DELETE SET NULL,
    case_id UUID REFERENCES legal.cases(id) ON DELETE SET NULL,
    guest_name VARCHAR(150) NOT NULL, guest_phone VARCHAR(30) NOT NULL, topic VARCHAR(1000) NOT NULL DEFAULT '',
    starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DENIED','CANCELLED','DONE','NO_SHOW')),
    decision_note VARCHAR(500) NOT NULL DEFAULT '',
    source VARCHAR(10) NOT NULL DEFAULT 'WEBSITE',
    reminder_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_legal_bookings_tenant ON legal.bookings (tenant_id, starts_at);
CREATE INDEX idx_legal_bookings_phone ON legal.bookings (tenant_id, guest_phone);

-- free notes on the calendar, optionally tied to a case
CREATE TABLE legal.calendar_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    case_id UUID REFERENCES legal.cases(id) ON DELETE SET NULL,
    starts_at TIMESTAMPTZ NOT NULL, all_day BOOLEAN NOT NULL DEFAULT FALSE,
    title VARCHAR(250) NOT NULL, body TEXT NOT NULL DEFAULT '', color VARCHAR(12) NOT NULL DEFAULT '',
    reminder_sent_at TIMESTAMPTZ,
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_legal_notes_tenant ON legal.calendar_notes (tenant_id, starts_at);

CREATE TABLE legal.counters (tenant_id UUID NOT NULL, year INT NOT NULL, seq INT NOT NULL, PRIMARY KEY (tenant_id, year));

CREATE TABLE legal.digest_sent (tenant_id UUID NOT NULL, day DATE NOT NULL, PRIMARY KEY (tenant_id, day));

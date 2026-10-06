CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA IF NOT EXISTS medical;

-- permissions for the clinic module -------------------------------------------------------------------
INSERT INTO core.permissions (code, name) VALUES
 ('patient.create','Register patients'),('schedule.manage','Manage schedules & services'),
 ('lab_result.upload','Upload lab results'),('lab_result.review','Review lab results'),('patient.export','Export patient record');
CREATE OR REPLACE FUNCTION pg_temp.grant_perms(role_code TEXT, perm_codes TEXT[]) RETURNS VOID AS $$
    INSERT INTO core.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM core.roles r, core.permissions p
    WHERE r.code = role_code AND r.tenant_id IS NULL AND p.code = ANY(perm_codes) ON CONFLICT DO NOTHING;
$$ LANGUAGE sql;
SELECT pg_temp.grant_perms('CLINIC_OWNER', ARRAY['patient.create','schedule.manage','lab_result.upload','lab_result.review','patient.export']);
SELECT pg_temp.grant_perms('DOCTOR', ARRAY['patient.create','schedule.manage','lab_result.upload','lab_result.review','patient.export']);
SELECT pg_temp.grant_perms('CLINIC_ADMIN', ARRAY['patient.create','schedule.manage']);
SELECT pg_temp.grant_perms('RECEPTIONIST', ARRAY['patient.create','lab_result.upload']);
SELECT pg_temp.grant_perms('LAB_COORDINATOR', ARRAY['lab_result.upload']);

CREATE TABLE medical.specialties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(40) NOT NULL UNIQUE, name_ar VARCHAR(100) NOT NULL, name_en VARCHAR(100) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO medical.specialties (code, name_ar, name_en) VALUES
 ('general_practice','طب عام','General practice'),('family_medicine','طب الأسرة','Family medicine'),('pediatrics','طب الأطفال','Pediatrics'),
 ('internal_medicine','الباطنة','Internal medicine'),('cardiology','القلب','Cardiology'),('dermatology','الجلدية','Dermatology'),
 ('orthopedics','العظام','Orthopedics'),('obgyn','النساء والتوليد','Obstetrics & gynecology'),('ent','الأنف والأذن والحنجرة','ENT'),
 ('ophthalmology','العيون','Ophthalmology'),('dentistry','الأسنان','Dentistry'),('neurology','المخ والأعصاب','Neurology'),
 ('psychiatry','الطب النفسي','Psychiatry'),('urology','المسالك البولية','Urology'),('endocrinology','الغدد الصماء','Endocrinology'),
 ('nutrition','التغذية','Nutrition'),('physiotherapy','العلاج الطبيعي','Physiotherapy'),('general_surgery','الجراحة العامة','General surgery');

CREATE TABLE medical.clinic_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES core.tenants(id),
    clinic_name VARCHAR(200) NOT NULL, about TEXT, phone VARCHAR(30), email VARCHAR(254), address_text VARCHAR(300), website_text VARCHAR(200),
    booking_enabled BOOLEAN NOT NULL DEFAULT TRUE, take_new_patients BOOLEAN NOT NULL DEFAULT TRUE,
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    minimum_booking_notice_minutes INT NOT NULL DEFAULT 60, maximum_days_ahead INT NOT NULL DEFAULT 60,
    cancellation_window_hours INT NOT NULL DEFAULT 2,
    card_enabled BOOLEAN NOT NULL DEFAULT FALSE, cash_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE medical.clinic_branches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(120) NOT NULL, code VARCHAR(30) NOT NULL,
    address_line1 VARCHAR(200) NOT NULL DEFAULT '', address_line2 VARCHAR(200), city VARCHAR(100) NOT NULL DEFAULT '', district VARCHAR(100),
    phone VARCHAR(30), latitude NUMERIC(9,6), longitude NUMERIC(9,6), is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

-- A clinic may have several doctors, so tenant_id is not unique here (the spec's single-doctor UNIQUE would block multi-doctor clinics).
CREATE TABLE medical.doctors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    user_id UUID NOT NULL REFERENCES core.users(id),
    display_name VARCHAR(150) NOT NULL, bio TEXT, gender VARCHAR(10), license_number VARCHAR(60),
    verification_status VARCHAR(12) NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED')),
    consultation_duration_minutes INT NOT NULL DEFAULT 30, default_appointment_fee_minor BIGINT,
    currency VARCHAR(3) NOT NULL DEFAULT 'EGP', timezone VARCHAR(60) NOT NULL DEFAULT 'Africa/Cairo', profile_image_file_id UUID,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id)
);
CREATE TABLE medical.doctor_specialties (
    doctor_id UUID NOT NULL REFERENCES medical.doctors(id) ON DELETE CASCADE,
    specialty_id UUID NOT NULL REFERENCES medical.specialties(id), PRIMARY KEY (doctor_id, specialty_id)
);

CREATE TABLE medical.patients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    user_id UUID REFERENCES core.users(id),                -- NULL until the person claims the record
    patient_code VARCHAR(20) NOT NULL,
    first_name VARCHAR(100) NOT NULL, last_name VARCHAR(100) NOT NULL DEFAULT '',
    date_of_birth DATE, sex VARCHAR(10), phone VARCHAR(20), email VARCHAR(254), address_text TEXT,
    emergency_contact_json JSONB, blood_type VARCHAR(5), notes_internal TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    link_pin_hash VARCHAR(255), link_pin_expires_at TIMESTAMPTZ, link_attempts INT NOT NULL DEFAULT 0,
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, patient_code)
);
CREATE INDEX idx_patients_phone ON medical.patients (tenant_id, phone);
CREATE INDEX idx_patients_user ON medical.patients (tenant_id, user_id);
CREATE INDEX idx_patients_name_trgm ON medical.patients USING gin ((first_name || ' ' || last_name) gin_trgm_ops);

CREATE TABLE medical.patient_guardians (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, patient_id UUID NOT NULL REFERENCES medical.patients(id),
    guardian_user_id UUID NOT NULL REFERENCES core.users(id), relationship VARCHAR(30) NOT NULL, is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (patient_id, guardian_user_id)
);

CREATE TABLE medical.appointment_services (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    name VARCHAR(120) NOT NULL, description TEXT, duration_minutes INT NOT NULL CHECK (duration_minutes BETWEEN 5 AND 480),
    price_minor BIGINT CHECK (price_minor IS NULL OR price_minor >= 0), currency VARCHAR(3) NOT NULL DEFAULT 'EGP', is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE medical.doctor_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id), doctor_id UUID NOT NULL REFERENCES medical.doctors(id), branch_id UUID NOT NULL REFERENCES medical.clinic_branches(id),
    weekday INT NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO: 1 = Monday ... 7 = Sunday
    start_local_time TIME NOT NULL, end_local_time TIME NOT NULL CHECK (end_local_time > start_local_time),
    slot_duration_minutes INT NOT NULL DEFAULT 30 CHECK (slot_duration_minutes >= 5), buffer_minutes INT NOT NULL DEFAULT 0 CHECK (buffer_minutes >= 0),
    effective_from DATE, effective_to DATE, is_active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_schedules_doctor ON medical.doctor_schedules (tenant_id, doctor_id, weekday);

CREATE TABLE medical.schedule_exceptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id), doctor_id UUID NOT NULL REFERENCES medical.doctors(id), branch_id UUID REFERENCES medical.clinic_branches(id),
    exception_date DATE NOT NULL, start_local_time TIME, end_local_time TIME,
    type VARCHAR(15) NOT NULL CHECK (type IN ('DAY_OFF','CUSTOM_HOURS','HOLIDAY','FULL_BOOKED')), reason VARCHAR(200)
);
CREATE INDEX idx_exceptions_doctor_date ON medical.schedule_exceptions (tenant_id, doctor_id, exception_date);

CREATE TABLE medical.appointments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    patient_id UUID NOT NULL REFERENCES medical.patients(id), doctor_id UUID NOT NULL REFERENCES medical.doctors(id),
    branch_id UUID NOT NULL REFERENCES medical.clinic_branches(id), service_id UUID NOT NULL REFERENCES medical.appointment_services(id),
    start_at TIMESTAMPTZ NOT NULL, end_at TIMESTAMPTZ NOT NULL CHECK (end_at > start_at),
    status VARCHAR(25) NOT NULL CHECK (status IN ('REQUESTED','PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW','REJECTED')),
    booking_source VARCHAR(15) NOT NULL CHECK (booking_source IN ('PATIENT_APP','PATIENT_WEB','RECEPTION','PHONE','ADMIN')),
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH_AT_CLINIC' CHECK (payment_method IN ('CARD','CASH_AT_CLINIC')),
    payment_required BOOLEAN NOT NULL DEFAULT FALSE,
    payment_status VARCHAR(15) NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PENDING','PAID','REFUNDED')),
    price_minor BIGINT, currency VARCHAR(3) NOT NULL DEFAULT 'EGP',
    patient_note TEXT, internal_note TEXT, cancel_reason TEXT, payment_provider_id VARCHAR(100), hold_expires_at TIMESTAMPTZ, queue_number INT,
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A doctor can never have two live appointments overlapping, regardless of application bugs or races.
    CONSTRAINT appointments_no_overlap EXCLUDE USING gist (doctor_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (status IN ('REQUESTED','PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','IN_PROGRESS'))
);
CREATE INDEX idx_appt_doctor_start ON medical.appointments (tenant_id, doctor_id, start_at);
CREATE INDEX idx_appt_patient_start ON medical.appointments (tenant_id, patient_id, start_at);

CREATE TABLE medical.encounters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id), patient_id UUID NOT NULL REFERENCES medical.patients(id), doctor_id UUID NOT NULL REFERENCES medical.doctors(id),
    appointment_id UUID REFERENCES medical.appointments(id), branch_id UUID REFERENCES medical.clinic_branches(id),
    visit_at TIMESTAMPTZ NOT NULL DEFAULT now(), chief_complaint TEXT, clinical_summary TEXT, follow_up_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_encounters_patient ON medical.encounters (tenant_id, patient_id, visit_at DESC);

CREATE TABLE medical.vitals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, encounter_id UUID NOT NULL REFERENCES medical.encounters(id),
    height_cm NUMERIC(5,1), weight_kg NUMERIC(5,1), temperature_c NUMERIC(4,1), heart_rate_bpm INT, respiratory_rate INT,
    systolic_bp INT, diastolic_bp INT, oxygen_saturation NUMERIC(4,1), notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE medical.conditions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, patient_id UUID NOT NULL REFERENCES medical.patients(id),
    encounter_id UUID REFERENCES medical.encounters(id), name VARCHAR(200) NOT NULL, code_system VARCHAR(30), code VARCHAR(30),
    status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESOLVED','INACTIVE')), onset_date DATE, resolved_date DATE, notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE medical.notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    patient_id UUID NOT NULL REFERENCES medical.patients(id), encounter_id UUID NOT NULL REFERENCES medical.encounters(id), author_user_id UUID NOT NULL,
    note_type VARCHAR(20) NOT NULL DEFAULT 'CLINICAL' CHECK (note_type IN ('CLINICAL','PROGRESS','INSTRUCTION','ADMINISTRATIVE')),
    content TEXT NOT NULL, is_patient_visible BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- append-only history: every edit keeps what was there before (spec 2.7 / 75)
CREATE TABLE medical.note_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, note_id UUID NOT NULL REFERENCES medical.notes(id),
    content TEXT NOT NULL, was_patient_visible BOOLEAN NOT NULL, changed_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE medical.prescriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    patient_id UUID NOT NULL REFERENCES medical.patients(id), encounter_id UUID NOT NULL REFERENCES medical.encounters(id), prescribed_by UUID NOT NULL REFERENCES medical.doctors(id),
    status VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ISSUED','CANCELLED')), issued_at TIMESTAMPTZ, notes TEXT, pdf_file_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prescriptions_patient ON medical.prescriptions (tenant_id, patient_id, issued_at DESC);
CREATE TABLE medical.prescription_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), prescription_id UUID NOT NULL REFERENCES medical.prescriptions(id),
    medication_name VARCHAR(200) NOT NULL, generic_name VARCHAR(200), strength VARCHAR(60), dosage VARCHAR(120), route VARCHAR(40),
    frequency VARCHAR(120), duration VARCHAR(120), quantity VARCHAR(60), instructions TEXT, sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE medical.prescription_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, prescription_id UUID NOT NULL REFERENCES medical.prescriptions(id),
    event VARCHAR(20) NOT NULL, snapshot JSONB NOT NULL, changed_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE medical.lab_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    patient_id UUID NOT NULL REFERENCES medical.patients(id), encounter_id UUID NOT NULL REFERENCES medical.encounters(id), ordered_by UUID NOT NULL REFERENCES medical.doctors(id),
    test_name VARCHAR(200) NOT NULL, instructions TEXT, priority VARCHAR(10) NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE','URGENT')),
    status VARCHAR(20) NOT NULL DEFAULT 'ORDERED' CHECK (status IN ('ORDERED','PATIENT_UPLOADED','UNDER_REVIEW','REVIEWED','CANCELLED')),
    ordered_at TIMESTAMPTZ NOT NULL DEFAULT now(), due_date DATE, reviewed_at TIMESTAMPTZ, reviewed_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lab_orders_patient ON medical.lab_orders (tenant_id, patient_id, ordered_at DESC);

CREATE TABLE medical.lab_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, lab_order_id UUID NOT NULL REFERENCES medical.lab_orders(id),
    file_id UUID, result_text TEXT, result_summary TEXT, uploaded_by UUID NOT NULL, uploaded_by_patient BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_at TIMESTAMPTZ, reviewed_by UUID, patient_visible BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE medical.medical_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    patient_id UUID NOT NULL REFERENCES medical.patients(id), encounter_id UUID REFERENCES medical.encounters(id), file_id UUID,
    document_type VARCHAR(20) NOT NULL CHECK (document_type IN ('LAB_RESULT','SCAN','RADIOLOGY','PRESCRIPTION','REFERRAL','DISCHARGE_SUMMARY','OTHER')),
    title VARCHAR(200) NOT NULL, description TEXT, source VARCHAR(10) NOT NULL DEFAULT 'CLINIC' CHECK (source IN ('CLINIC','PATIENT')),
    patient_visible BOOLEAN NOT NULL DEFAULT FALSE, created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_patient ON medical.medical_documents (tenant_id, patient_id);

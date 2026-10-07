ALTER TABLE medical.appointments ADD COLUMN checked_in_at TIMESTAMPTZ, ADD COLUMN called_at TIMESTAMPTZ;
CREATE INDEX idx_appt_queue ON medical.appointments (tenant_id, doctor_id, queue_number) WHERE status IN ('CHECKED_IN','IN_PROGRESS');

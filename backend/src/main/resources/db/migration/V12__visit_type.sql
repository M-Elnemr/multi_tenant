-- Consultation (كشف) or follow-up (إعادة): chosen when the patient joins the queue, shown in the patient history.
ALTER TABLE medical.appointment_services ADD COLUMN visit_type VARCHAR(12) CHECK (visit_type IN ('CONSULTATION', 'FOLLOW_UP'));
ALTER TABLE medical.appointments ADD COLUMN visit_type VARCHAR(12) NOT NULL DEFAULT 'CONSULTATION' CHECK (visit_type IN ('CONSULTATION', 'FOLLOW_UP'));

-- Existing data: the default services are told apart by name; everything unclear stays a consultation.
UPDATE medical.appointment_services SET visit_type = 'FOLLOW_UP' WHERE lower(name) LIKE 'follow%' OR name LIKE '%إعادة%' OR name LIKE '%متابعة%';
UPDATE medical.appointment_services SET visit_type = 'CONSULTATION' WHERE visit_type IS NULL AND (lower(name) LIKE 'consult%' OR name LIKE '%كشف%');
UPDATE medical.appointments SET visit_type = 'FOLLOW_UP' WHERE service_id IN (SELECT id FROM medical.appointment_services WHERE visit_type = 'FOLLOW_UP');

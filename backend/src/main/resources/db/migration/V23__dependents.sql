-- Dependents: a child (no phone, no login) belongs to a parent patient of the same clinic.
-- Access still goes through medical.patient_guardians (parent's account); this column lets the clinic list children under the parent
-- and lets us restore the guardian link if the parent leaves and is added again.
ALTER TABLE medical.patients ADD COLUMN guardian_patient_id uuid REFERENCES medical.patients(id);
CREATE INDEX patients_guardian_patient_idx ON medical.patients (guardian_patient_id) WHERE guardian_patient_id IS NOT NULL;

UPDATE medical.patients c SET guardian_patient_id = p.id
FROM medical.patient_guardians g JOIN medical.patients p ON p.user_id = g.guardian_user_id AND p.tenant_id = g.tenant_id
WHERE c.id = g.patient_id AND c.tenant_id = g.tenant_id AND p.id <> c.id AND c.guardian_patient_id IS NULL;

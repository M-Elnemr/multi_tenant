-- The patient number shown everywhere is the 6-digit platform ID: a patient's code in each clinic becomes that number,
-- and a child's code is the parent's number plus -1, -2... (children have no account of their own).
UPDATE medical.patients p SET patient_code = a.public_id::text
FROM medical.patient_accounts a
WHERE a.id = p.user_id AND p.guardian_patient_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM medical.patients o WHERE o.tenant_id = p.tenant_id AND o.patient_code = a.public_id::text AND o.id <> p.id);

UPDATE medical.patients c SET patient_code = n.code
FROM (
  SELECT ch.id, par.patient_code || '-' || row_number() OVER (PARTITION BY ch.guardian_patient_id ORDER BY ch.created_at, ch.id) AS code
  FROM medical.patients ch JOIN medical.patients par ON par.id = ch.guardian_patient_id
  WHERE par.patient_code ~ '^[0-9]+$'
) n
WHERE c.id = n.id
  AND NOT EXISTS (SELECT 1 FROM medical.patients o WHERE o.tenant_id = c.tenant_id AND o.patient_code = n.code AND o.id <> c.id);

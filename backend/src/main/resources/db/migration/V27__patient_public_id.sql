-- A short platform-wide number for every patient account (6 digits now; the allocator moves to 7-9 digits when the range fills up).
-- It is shown with a QR/barcode in the patient's profile, works as a login name, and lets a clinic add an existing patient.
ALTER TABLE medical.patient_accounts ADD COLUMN public_id INT;

DO $$
DECLARE r RECORD; n INT;
BEGIN
  FOR r IN SELECT id FROM medical.patient_accounts ORDER BY created_at LOOP
    LOOP
      n := 100000 + floor(random() * 900000)::int;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM medical.patient_accounts WHERE public_id = n);
    END LOOP;
    UPDATE medical.patient_accounts SET public_id = n WHERE id = r.id;
  END LOOP;
END $$;

ALTER TABLE medical.patient_accounts ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE medical.patient_accounts ADD CONSTRAINT patient_accounts_public_id_key UNIQUE (public_id);

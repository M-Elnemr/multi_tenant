-- A law office's website: ten designs (the three original ones stay, so no published office changes). The list must match LegalProfileService and web/lib/lawyer-templates.ts.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE contype = 'c' AND conrelid = 'legal.profiles'::regclass AND pg_get_constraintdef(oid) LIKE '%template%'
    LOOP EXECUTE format('ALTER TABLE legal.profiles DROP CONSTRAINT %I', c.conname); END LOOP;
END $$;
ALTER TABLE legal.profiles ADD CONSTRAINT profiles_template_check CHECK (template IN ('classic','modern','heritage','chambers','justice','marble','gavel','minimal','emerald','nocturne'));

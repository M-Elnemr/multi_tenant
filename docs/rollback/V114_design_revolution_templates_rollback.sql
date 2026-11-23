-- Rollback for V114. Run only after the previous application image is running (it does not know the new ids).
-- Sites that picked a new design go back to the default of their kind; nothing else changes.
UPDATE commerce.store_profiles SET template = 'original' WHERE template LIKE 'sn-%' OR template LIKE 'pn-%';
UPDATE commerce.store_profiles SET template = 'lumiere' WHERE template LIKE 'vn-%';
UPDATE medical.clinic_profiles SET template = 'clear' WHERE template LIKE 'cn-%';
UPDATE legal.profiles SET template = 'classic' WHERE template LIKE 'ln-%';
ALTER TABLE commerce.store_profiles DROP CONSTRAINT IF EXISTS store_profiles_template_check;
ALTER TABLE commerce.store_profiles ADD CONSTRAINT store_profiles_template_check CHECK (template IN (
    'original', 'atelier', 'street', 'grove', 'midnight', 'maison', 'feast', 'bloom', 'oud', 'souq',
    'lumiere', 'barbershop', 'homefix', 'fixit', 'aqua', 'sparkle', 'garage', 'pro', 'slot', 'bazaar',
    'solo', 'vitrine', 'capsule', 'monolith', 'pedestal', 'spotlight', 'launch', 'signature', 'curated', 'origin'));
ALTER TABLE medical.clinic_profiles DROP CONSTRAINT IF EXISTS clinic_profiles_template_check;
ALTER TABLE medical.clinic_profiles ADD CONSTRAINT clinic_profiles_template_check CHECK (template IN ('clear', 'luxe', 'persona', 'dental', 'derma', 'center'));
ALTER TABLE legal.profiles DROP CONSTRAINT IF EXISTS profiles_template_check;
ALTER TABLE legal.profiles ADD CONSTRAINT profiles_template_check CHECK (template IN ('classic','modern','heritage','chambers','justice','marble','gavel','minimal','emerald','nocturne'));
-- V113 (site_theme) is nullable and harmless to keep; to remove: ALTER TABLE ... DROP COLUMN site_theme on the three tables.

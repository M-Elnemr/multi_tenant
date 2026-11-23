-- Design revolution: 25 new website designs (5 per business kind). Old ids stay valid, so no published site changes; the picker simply stops offering them.
-- The lists must match StoreSettingsService, ClinicSettingsService, LegalProfileService and web/lib/template-palettes.ts. All ids fit VARCHAR(16).
ALTER TABLE commerce.store_profiles DROP CONSTRAINT IF EXISTS store_profiles_template_check;
ALTER TABLE commerce.store_profiles ADD CONSTRAINT store_profiles_template_check CHECK (template IN (
    'original', 'atelier', 'street', 'grove', 'midnight', 'maison', 'feast', 'bloom', 'oud', 'souq',
    'lumiere', 'barbershop', 'homefix', 'fixit', 'aqua', 'sparkle', 'garage', 'pro', 'slot', 'bazaar',
    'solo', 'vitrine', 'capsule', 'monolith', 'pedestal', 'spotlight', 'launch', 'signature', 'curated', 'origin',
    'sn-editorial', 'sn-market', 'sn-minimal', 'sn-bold', 'sn-lifestyle',
    'pn-flagship', 'pn-showcase', 'pn-trio', 'pn-launch', 'pn-brand',
    'vn-wellness', 'vn-homepro', 'vn-repair', 'vn-studio', 'vn-multi'));

ALTER TABLE medical.clinic_profiles DROP CONSTRAINT IF EXISTS clinic_profiles_template_check;
ALTER TABLE medical.clinic_profiles ADD CONSTRAINT clinic_profiles_template_check CHECK (template IN (
    'clear', 'luxe', 'persona', 'dental', 'derma', 'center',
    'cn-institute', 'cn-boutique', 'cn-medtech', 'cn-family', 'cn-atelier'));

DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE contype = 'c' AND conrelid = 'legal.profiles'::regclass AND pg_get_constraintdef(oid) LIKE '%template%'
    LOOP EXECUTE format('ALTER TABLE legal.profiles DROP CONSTRAINT %I', c.conname); END LOOP;
END $$;
ALTER TABLE legal.profiles ADD CONSTRAINT profiles_template_check CHECK (template IN (
    'classic', 'modern', 'heritage', 'chambers', 'justice', 'marble', 'gavel', 'minimal', 'emerald', 'nocturne',
    'ln-global', 'ln-boutique', 'ln-advisory', 'ln-digital', 'ln-heritage'));

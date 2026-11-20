-- Ten website designs for a shop with one to three products (selectable only while the shop has 1-3 active products; the backend decides, the published design is never removed when the catalog grows).
ALTER TABLE commerce.store_profiles DROP CONSTRAINT IF EXISTS store_profiles_template_check;
ALTER TABLE commerce.store_profiles ADD CONSTRAINT store_profiles_template_check CHECK (template IN (
    'original', 'atelier', 'street', 'grove', 'midnight', 'maison', 'feast', 'bloom', 'oud', 'souq',
    'lumiere', 'barbershop', 'homefix', 'fixit', 'aqua', 'sparkle', 'garage', 'pro', 'slot', 'bazaar',
    'solo', 'vitrine', 'capsule', 'monolith', 'pedestal', 'spotlight', 'launch', 'signature', 'curated', 'origin'));

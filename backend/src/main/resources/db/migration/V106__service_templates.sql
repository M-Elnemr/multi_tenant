-- Ten website designs only a SERVICE business can choose. The old list stays valid for shops; the backend decides who may pick which.
ALTER TABLE commerce.store_profiles DROP CONSTRAINT IF EXISTS store_profiles_template_check;
ALTER TABLE commerce.store_profiles ADD CONSTRAINT store_profiles_template_check CHECK (template IN (
    'original', 'atelier', 'street', 'grove', 'midnight', 'maison', 'feast', 'bloom', 'oud', 'souq',
    'lumiere', 'barbershop', 'homefix', 'fixit', 'aqua', 'sparkle', 'garage', 'pro', 'slot', 'bazaar'));

-- Four more shop designs (street, grove, maison, feast). Each template now has its own layout composition on the website (header, hero, product
-- cards, category navigation), not only colours. The list here must match StoreSettingsService.TEMPLATES and web/components/shop/templates/ids.ts.
ALTER TABLE commerce.store_profiles DROP CONSTRAINT IF EXISTS store_profiles_template_check;
ALTER TABLE commerce.store_profiles ADD CONSTRAINT store_profiles_template_check
    CHECK (template IN ('original', 'atelier', 'street', 'grove', 'midnight', 'maison', 'feast', 'bloom', 'oud', 'souq'));

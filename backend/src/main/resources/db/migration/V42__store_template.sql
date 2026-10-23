-- The look of the shop's website: one of a few ready-made templates. 'original' is the design every shop had before, so nothing changes until the owner picks another.
-- Presentation only: no template changes what the shop can do. To remove the feature, drop this column and the web/components/shop/templates folder.
ALTER TABLE commerce.store_profiles ADD COLUMN template VARCHAR(16) NOT NULL DEFAULT 'original'
    CHECK (template IN ('original', 'atelier', 'souq', 'midnight', 'bloom', 'oud'));

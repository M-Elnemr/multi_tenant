-- Marketing tags a shop owner can switch on (ads and analytics); injected into the storefront only when set.
ALTER TABLE commerce.store_profiles
    ADD COLUMN meta_pixel_id VARCHAR(40) NOT NULL DEFAULT '',
    ADD COLUMN tiktok_pixel_id VARCHAR(40) NOT NULL DEFAULT '',
    ADD COLUMN ga_id VARCHAR(40) NOT NULL DEFAULT '';

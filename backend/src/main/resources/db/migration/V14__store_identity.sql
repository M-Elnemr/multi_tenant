-- Store identity: contact channels, hours, vacation mode, tax/VAT, cover image; richer branches.
ALTER TABLE commerce.store_profiles
    ADD COLUMN whatsapp VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN extra_phones JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN facebook_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN instagram_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN tiktok_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN website_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN maps_url VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN working_hours JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN closed_message VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN min_order_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tax_id VARCHAR(40) NOT NULL DEFAULT '',
    ADD COLUMN vat_included BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN vat_percent NUMERIC(5,2) NOT NULL DEFAULT 14,
    ADD COLUMN cover_file_id UUID,
    ADD COLUMN announcement VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN return_window_days INT NOT NULL DEFAULT 14;

ALTER TABLE commerce.branches
    ADD COLUMN whatsapp VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN governorate_code VARCHAR(10) NOT NULL DEFAULT '',
    ADD COLUMN area VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN landmark VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN working_hours JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN maps_url VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN is_pickup BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- Clinic identity for the public website: contact channels, hours, cover/gallery, announcement, insurance, FAQs; richer doctors and branches.
ALTER TABLE medical.clinic_profiles
    ADD COLUMN tagline VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN whatsapp VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN extra_phones JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN facebook_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN instagram_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN tiktok_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN website_url VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN maps_url VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN working_hours JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN cover_file_id UUID,
    ADD COLUMN gallery JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN announcement VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN closed_message VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN insurance JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN faqs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN established_year INT;

ALTER TABLE medical.doctors
    ADD COLUMN years_experience INT,
    ADD COLUMN qualifications VARCHAR(400) NOT NULL DEFAULT '',
    ADD COLUMN languages JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE medical.clinic_branches
    ADD COLUMN whatsapp VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN maps_url VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN landmark VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN working_hours JSONB NOT NULL DEFAULT '{}'::jsonb;

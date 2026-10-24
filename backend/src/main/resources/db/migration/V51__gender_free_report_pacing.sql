-- Customer gender (to speak to each customer correctly in Arabic), the free daily report on the shop's own number, and paced free broadcasts.
ALTER TABLE commerce.customers ADD COLUMN gender VARCHAR(6) CHECK (gender IN ('MALE', 'FEMALE')), ADD COLUMN gender_source VARCHAR(6) CHECK (gender_source IN ('AI', 'SELF', 'OWNER'));
ALTER TABLE commerce.client_accounts ADD COLUMN gender VARCHAR(6) CHECK (gender IN ('MALE', 'FEMALE'));
ALTER TABLE commerce.broadcast_recipients ADD COLUMN gender VARCHAR(6);
ALTER TABLE commerce.broadcasts ADD COLUMN last_sent_at TIMESTAMPTZ;

ALTER TABLE chat.evolution_settings
    ADD COLUMN report_mode VARCHAR(4) NOT NULL DEFAULT 'PAID' CHECK (report_mode IN ('PAID', 'FREE')),
    ADD COLUMN report_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN report_send_after TIME NOT NULL DEFAULT '23:00',
    ADD COLUMN report_language VARCHAR(2) NOT NULL DEFAULT 'ar' CHECK (report_language IN ('ar', 'en'));

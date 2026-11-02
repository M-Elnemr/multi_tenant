-- 1) A lawyer's client can sign in to the office app with the phone the lawyer saved and a temporary password the lawyer gives
--    (changed at the first sign-in). Only clients the lawyer added, and only when the lawyer turned their access on.
ALTER TABLE legal.clients
    ADD COLUMN portal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN portal_password_hash VARCHAR(255),
    ADD COLUMN portal_must_change BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN portal_last_login_at TIMESTAMPTZ;

-- reminders the client gets: the day before a hearing / meeting of their case, an hour before their session
ALTER TABLE legal.case_steps ADD COLUMN client_reminder_sent_at TIMESTAMPTZ;
ALTER TABLE legal.bookings ADD COLUMN client_reminder_sent_at TIMESTAMPTZ;

-- sessions of a client of a law office
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'core.user_sessions'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%principal_type%' LOOP
    EXECUTE format('ALTER TABLE core.user_sessions DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;
ALTER TABLE core.user_sessions ADD CONSTRAINT user_sessions_principal_type_check CHECK (principal_type IN ('STAFF', 'PATIENT', 'CLIENT', 'LAWCLIENT'));

-- 2) the evening report on the lawyer's own WhatsApp (sent from his linked number to itself) and the link clients get to the office app
ALTER TABLE legal.profiles
    ADD COLUMN wa_report BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN client_app_url VARCHAR(300) NOT NULL DEFAULT '';
UPDATE legal.profiles SET client_app_url = 'https://play.google.com/store/apps/details?id=com.elhusseiny.lawyer'
WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = 'elhosseiny');

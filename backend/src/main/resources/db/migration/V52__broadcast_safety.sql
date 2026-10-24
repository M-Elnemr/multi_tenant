-- Safer free broadcasts: random gaps, a daily cap, quiet hours, automatic pause, and a way for customers to opt out.
ALTER TABLE commerce.broadcasts DROP CONSTRAINT broadcasts_status_check;
ALTER TABLE commerce.broadcasts ADD CONSTRAINT broadcasts_status_check CHECK (status IN ('SENDING', 'DONE', 'PAUSED', 'CANCELED'));
ALTER TABLE commerce.broadcasts
    ADD COLUMN pause_reason VARCHAR(16),            -- why nothing is going out right now: QUIET_HOURS, DAILY_CAP, FAILURES, OPT_OUTS
    ADD COLUMN next_send_at TIMESTAMPTZ,
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE commerce.broadcast_recipients DROP CONSTRAINT broadcast_recipients_status_check;
ALTER TABLE commerce.broadcast_recipients ADD CONSTRAINT broadcast_recipients_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'STOPPED'));
ALTER TABLE commerce.broadcast_recipients ADD COLUMN opted_out_at TIMESTAMPTZ;
ALTER TABLE commerce.customers ADD COLUMN broadcast_opt_out_at TIMESTAMPTZ;

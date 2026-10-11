-- Phone pushes can carry a small JSON payload (type, appointment, position) so the app can refresh and open the right screen.
ALTER TABLE notifications.outbox ADD COLUMN payload JSONB;

-- The last "N patients before you" number we pushed for an appointment, so a change is announced once and never repeated.
CREATE TABLE medical.queue_push_state (
    appointment_id UUID PRIMARY KEY REFERENCES medical.appointments(id) ON DELETE CASCADE,
    last_ahead INT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

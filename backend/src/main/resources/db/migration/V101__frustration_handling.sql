-- Customer frustration handling: the shop chooses what happens when a customer is genuinely upset (the assistant calms them itself, sends one fixed message and hands over, or stays silent and hands over).
ALTER TABLE chat.settings
    ADD COLUMN frustration_mode    VARCHAR(8) NOT NULL DEFAULT 'AI' CHECK (frustration_mode IN ('AI', 'MESSAGE', 'SILENT')),
    ADD COLUMN frustration_message TEXT;

-- A handed-over chat: the assistant stays out of it until the shop resumes it by hand. A separate state from a person taking the chat over, a timed pause and an empty wallet.
ALTER TABLE chat.conversations
    ADD COLUMN frustration_paused  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN frustration_at      TIMESTAMPTZ,
    ADD COLUMN frustration_reason  VARCHAR(300);

-- Questions the AI seller could not answer become a conversation with the shop owner: the owner is told, answers in the dashboard, and the assistant then carries the answer to the customer in the same chat.
ALTER TABLE chat.unanswered ADD COLUMN status        VARCHAR(10) NOT NULL DEFAULT 'OPEN';   -- OPEN, ANSWERED, DISMISSED
ALTER TABLE chat.unanswered ADD COLUMN answer        VARCHAR(2000);                          -- what the owner wrote
ALTER TABLE chat.unanswered ADD COLUMN answered_at   TIMESTAMPTZ;
ALTER TABLE chat.unanswered ADD COLUMN answered_by   UUID;
ALTER TABLE chat.unanswered ADD COLUMN reply_text    VARCHAR(4000);                          -- the message the customer was sent (or should be sent)
ALTER TABLE chat.unanswered ADD COLUMN delivery      VARCHAR(10);                            -- SENT, SAVED (waiting in the website chat) or FAILED
ALTER TABLE chat.unanswered ADD COLUMN delivery_note VARCHAR(300);

UPDATE chat.unanswered SET status = CASE WHEN resolved THEN 'DISMISSED' ELSE 'OPEN' END;
CREATE INDEX idx_unanswered_status ON chat.unanswered (tenant_id, status, created_at DESC);

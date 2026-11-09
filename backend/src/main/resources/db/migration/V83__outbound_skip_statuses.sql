-- A confirmation or reminder that is not sent says why: no credit (0.1 EGP per message is charged before sending) or no linked own WhatsApp number.
ALTER TABLE chat.wa_outbound DROP CONSTRAINT wa_outbound_status_check;
ALTER TABLE chat.wa_outbound ADD CONSTRAINT wa_outbound_status_check CHECK (status IN ('SENT', 'FREE_TEXT', 'FAILED', 'WAITING_CREDIT', 'SKIPPED_NO_CREDIT', 'NO_OWN_NUMBER'));

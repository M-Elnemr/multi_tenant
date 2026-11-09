-- Round 4 pricing and growth rules.
--  * every outbound message (WhatsApp confirmation / reminder / offer / ad, Messenger and Instagram ad) costs 0.1 EGP (10 piasters), charged in code by billing.OutboundBilling
--  * an AI conversation costs 1 EGP per block of 10 AI replies (it was 1 EGP per 20 messages, counted +2 per turn): chat.conversations.ai_msgs now counts AI replies
--  * a chat remembers where it started (origin): a customer who answers a confirmation, a cart reminder, an ad, a follow-up or a comment message opens an AI conversation
--  * broadcasts can target several segments and several platforms (WhatsApp, Messenger, Instagram); recipients can be a Messenger / Instagram contact, not only a phone
--  * official WhatsApp (Meta Cloud API) is no longer used for sending: every send mode is the shop's own number

-- 1) conversation billing: ai_msgs becomes "AI replies" (the old count was 2 per turn); units already paid stay as they are
UPDATE chat.conversations SET ai_msgs = (ai_msgs + 1) / 2 WHERE ai_msgs > 0;

-- 2) where a chat started, and the platform window a Click-to-Messenger ad gives (7 days instead of 24 hours)
ALTER TABLE chat.conversations
    ADD COLUMN origin             VARCHAR(10) NOT NULL DEFAULT 'DIRECT' CHECK (origin IN ('DIRECT', 'CONFIRM', 'CART', 'BROADCAST', 'FOLLOWUP', 'COMMENT')),
    ADD COLUMN origin_at          TIMESTAMPTZ,
    ADD COLUMN window_until       TIMESTAMPTZ,
    ADD COLUMN broadcast_opt_out_at TIMESTAMPTZ;

ALTER TABLE chat.messages DROP CONSTRAINT messages_kind_check;
ALTER TABLE chat.messages ADD CONSTRAINT messages_kind_check CHECK (kind IN ('TEXT', 'FOLLOWUP', 'CONFIRM', 'CART', 'BROADCAST', 'COMMENT_DM'));
ALTER TABLE chat.messages ALTER COLUMN kind TYPE VARCHAR(12);

-- the message trigger also remembers the origin: the first customer message that comes right after one of our outbound messages
CREATE OR REPLACE FUNCTION chat.on_message_insert() RETURNS trigger AS $$
DECLARE
    c chat.conversations%ROWTYPE;
    prev_kind VARCHAR(12);
BEGIN
    SELECT * INTO c FROM chat.conversations WHERE id = NEW.conversation_id;
    IF NOT FOUND OR c.channel = 'TEST' THEN
        RETURN NEW;
    END IF;
    IF NEW.role = 'user' THEN
        IF c.origin = 'DIRECT' AND c.origin_at IS NULL THEN
            SELECT m.kind INTO prev_kind FROM chat.messages m
             WHERE m.conversation_id = NEW.conversation_id AND m.id <> NEW.id AND m.created_at <= NEW.created_at
             ORDER BY m.created_at DESC, m.id DESC LIMIT 1;
            IF prev_kind IN ('CONFIRM', 'CART', 'BROADCAST', 'FOLLOWUP', 'COMMENT_DM') THEN
                UPDATE chat.conversations SET origin_at = NEW.created_at,
                       origin = CASE prev_kind WHEN 'COMMENT_DM' THEN 'COMMENT' ELSE prev_kind END
                 WHERE id = NEW.conversation_id;
            END IF;
        END IF;
        UPDATE chat.conversations SET last_customer_at = NEW.created_at,
               status = 'OPEN', closed_at = CASE WHEN status = 'CLOSED' THEN NULL ELSE closed_at END
         WHERE id = NEW.conversation_id;
        UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'CUSTOMER_REPLIED'
         WHERE conversation_id = NEW.conversation_id AND status = 'SCHEDULED';
        UPDATE chat.auto_followups SET returned_at = NEW.created_at
         WHERE id = (SELECT f.id FROM chat.auto_followups f WHERE f.conversation_id = NEW.conversation_id AND f.status = 'SENT' AND f.returned_at IS NULL
                       AND f.sent_at > NEW.created_at - interval '24 hours' ORDER BY f.sent_at DESC LIMIT 1);
    ELSIF NEW.kind = 'TEXT' THEN
        UPDATE chat.conversations SET last_reply_at = NEW.created_at WHERE id = NEW.conversation_id;
        IF c.last_customer_at IS NOT NULL AND c.status = 'OPEN' AND NOT c.takeover AND NOT c.needs_human
           AND coalesce(c.ai_paused_until <= now(), TRUE)
           AND EXISTS (SELECT 1 FROM chat.followup_settings s WHERE s.tenant_id = c.tenant_id AND s.enabled) THEN
            INSERT INTO chat.auto_followups (tenant_id, conversation_id, step_position, anchor_customer_at, due_at, status, reason, body)
            SELECT c.tenant_id, c.id, st.position, c.last_customer_at, d.due,
                   CASE WHEN d.due > c.last_customer_at + interval '23 hours' THEN 'INELIGIBLE' ELSE 'SCHEDULED' END,
                   CASE WHEN d.due > c.last_customer_at + interval '23 hours' THEN 'OUTSIDE_WINDOW' END,
                   st.message
              FROM chat.followup_steps st
              CROSS JOIN LATERAL (SELECT greatest(c.last_customer_at + make_interval(mins => st.delay_minutes), NEW.created_at + interval '30 minutes') AS due) d
             WHERE st.tenant_id = c.tenant_id AND st.enabled
            ON CONFLICT (conversation_id, anchor_customer_at, step_position) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3) broadcasts: several segments and platforms; a recipient is a phone (WhatsApp) or a Messenger / Instagram contact; a message being sent now
ALTER TABLE commerce.broadcasts
    ADD COLUMN segments  TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN platforms TEXT[] NOT NULL DEFAULT '{WHATSAPP}';
UPDATE commerce.broadcasts SET segments = ARRAY[segment] WHERE segment IS NOT NULL;

ALTER TABLE commerce.broadcast_recipients
    ADD COLUMN channel     VARCHAR(10) NOT NULL DEFAULT 'WHATSAPP' CHECK (channel IN ('WHATSAPP', 'FACEBOOK', 'INSTAGRAM')),
    ADD COLUMN external_id VARCHAR(80),
    ADD COLUMN cost_minor  INT NOT NULL DEFAULT 0;
ALTER TABLE commerce.broadcast_recipients ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE commerce.broadcast_recipients DROP CONSTRAINT broadcast_recipients_status_check;
ALTER TABLE commerce.broadcast_recipients ADD CONSTRAINT broadcast_recipients_status_check CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'STOPPED', 'SKIPPED'));
ALTER TABLE commerce.broadcast_recipients ALTER COLUMN status TYPE VARCHAR(10);

-- 4) official WhatsApp sending is gone: every mode is the shop's own number
UPDATE chat.wa_settings SET send_mode = 'FREE';
UPDATE commerce.cart_settings SET send_mode = 'FREE', allow_paid = FALSE;
UPDATE commerce.broadcasts SET mode = 'FREE' WHERE mode IN ('PAID', 'NONE') AND status <> 'DONE';
ALTER TABLE chat.wa_settings ALTER COLUMN send_mode SET DEFAULT 'FREE';
ALTER TABLE commerce.cart_settings ALTER COLUMN send_mode SET DEFAULT 'FREE';
ALTER TABLE commerce.broadcasts ALTER COLUMN mode SET DEFAULT 'FREE';

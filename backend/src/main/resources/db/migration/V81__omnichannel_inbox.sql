-- Omnichannel inbox (2026-11): conversation state (open/closed, unread, human takeover, unhappy customer), a timeline of system events, internal notes,
-- saved replies, customer tags, automated free follow-up sequences inside the 24 h window, social comments (Facebook / Instagram) and the AI's own name.

-- 1) conversation state ------------------------------------------------------------------------------------------------
ALTER TABLE chat.conversations
    ADD COLUMN IF NOT EXISTS status           VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    ADD COLUMN IF NOT EXISTS closed_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_customer_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reply_at    TIMESTAMPTZ,      -- the AI's or the team's last message (follow-ups do not count)
    ADD COLUMN IF NOT EXISTS owner_read_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS takeover         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS takeover_by      UUID,
    ADD COLUMN IF NOT EXISTS takeover_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS unhappy          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS unhappy_reason   VARCHAR(300),
    ADD COLUMN IF NOT EXISTS unhappy_source   VARCHAR(16) CHECK (unhappy_source IN ('RULES', 'OWNER', 'OWNER_CLEARED')),
    ADD COLUMN IF NOT EXISTS unhappy_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS contact_phone    VARCHAR(30);      -- a phone the customer gave in the chat (website, Messenger, Instagram)

ALTER TABLE chat.messages
    ADD COLUMN IF NOT EXISTS kind      VARCHAR(12) NOT NULL DEFAULT 'TEXT' CHECK (kind IN ('TEXT', 'FOLLOWUP')),
    ADD COLUMN IF NOT EXISTS media_url VARCHAR(500);

UPDATE chat.conversations c SET
    last_customer_at = (SELECT max(m.created_at) FROM chat.messages m WHERE m.conversation_id = c.id AND m.role = 'user'),
    last_reply_at    = (SELECT max(m.created_at) FROM chat.messages m WHERE m.conversation_id = c.id AND m.role = 'assistant'),
    owner_read_at    = now();
CREATE INDEX IF NOT EXISTS idx_conversations_customer_at ON chat.conversations (tenant_id, last_customer_at DESC) WHERE channel <> 'TEST';
CREATE INDEX IF NOT EXISTS idx_conversations_status ON chat.conversations (tenant_id, status, updated_at DESC) WHERE channel <> 'TEST';

-- 2) timeline events, internal notes, saved replies, customer tags ------------------------------------------------------
CREATE TABLE chat.conversation_events (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    kind            VARCHAR(24) NOT NULL,
    actor_user_id   UUID,
    meta            JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_events_conv ON chat.conversation_events (conversation_id, created_at);

CREATE TABLE chat.conversation_notes (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    author_user_id  UUID,
    body            VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_notes_conv ON chat.conversation_notes (conversation_id, created_at);

CREATE TABLE chat.saved_replies (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    title      VARCHAR(80) NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_replies_tenant ON chat.saved_replies (tenant_id, title);

CREATE TABLE commerce.customer_tags (
    customer_id UUID NOT NULL REFERENCES commerce.customers(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    tag         VARCHAR(40) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, tag)
);
CREATE INDEX idx_customer_tags_tenant ON commerce.customer_tags (tenant_id, tag);

-- 3) automated free follow-ups: a sequence of up to 5 messages, each delay counted from the customer's last message ---------
CREATE TABLE chat.followup_settings (
    tenant_id  UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE chat.followup_steps (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    position      INT NOT NULL CHECK (position BETWEEN 1 AND 5),
    delay_minutes INT NOT NULL CHECK (delay_minutes BETWEEN 30 AND 1380),
    message       VARCHAR(600) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, position)
);
CREATE TABLE chat.auto_followups (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id    UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    step_position      INT NOT NULL,
    anchor_customer_at TIMESTAMPTZ NOT NULL,    -- the customer's last message that started this quiet period
    due_at             TIMESTAMPTZ NOT NULL,
    status             VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'SENT', 'CANCELLED', 'FAILED', 'INELIGIBLE')),
    reason             VARCHAR(200),
    body               VARCHAR(600) NOT NULL,
    attempts           INT NOT NULL DEFAULT 0,
    sent_at            TIMESTAMPTZ,
    returned_at        TIMESTAMPTZ,              -- the customer wrote again within 24 h of this follow-up
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, anchor_customer_at, step_position)   -- each step at most once per quiet period
);
CREATE INDEX idx_auto_followups_due ON chat.auto_followups (due_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_auto_followups_tenant ON chat.auto_followups (tenant_id, created_at DESC);

-- 4) social comments (Facebook / Instagram posts and ads) -------------------------------------------------------------------
CREATE TABLE chat.social_comments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    platform          VARCHAR(12) NOT NULL CHECK (platform IN ('FACEBOOK', 'INSTAGRAM')),
    comment_id        VARCHAR(120) NOT NULL UNIQUE,
    parent_comment_id VARCHAR(120),
    post_id           VARCHAR(120),
    post_permalink    VARCHAR(500),
    post_text         VARCHAR(2000),
    post_image        VARCHAR(500),
    is_ad             BOOLEAN NOT NULL DEFAULT FALSE,
    author_id         VARCHAR(120),
    author_name       VARCHAR(120),
    text              VARCHAR(2000) NOT NULL,
    question          BOOLEAN NOT NULL DEFAULT FALSE,
    status            VARCHAR(10) NOT NULL DEFAULT 'UNREPLIED' CHECK (status IN ('UNREPLIED', 'REPLIED', 'IGNORED')),
    reply_text        VARCHAR(2000),
    replied_by        VARCHAR(8) CHECK (replied_by IN ('AI', 'STAMP', 'OWNER')),
    replied_at        TIMESTAMPTZ,
    dm_status         VARCHAR(12) NOT NULL DEFAULT 'NONE' CHECK (dm_status IN ('NONE', 'SENT', 'FAILED', 'NOT_ALLOWED')),
    dm_text           VARCHAR(2000),
    dm_at             TIMESTAMPTZ,
    error             VARCHAR(300),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_social_comments_tenant ON chat.social_comments (tenant_id, created_at DESC);
CREATE INDEX idx_social_comments_unreplied ON chat.social_comments (tenant_id, created_at DESC) WHERE status = 'UNREPLIED';

CREATE TABLE chat.comment_settings (
    tenant_id         UUID PRIMARY KEY REFERENCES core.tenants(id) ON DELETE CASCADE,
    public_reply      VARCHAR(8) NOT NULL DEFAULT 'OFF' CHECK (public_reply IN ('OFF', 'STAMP', 'AI')),
    stamp_text        VARCHAR(300),
    dm_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    dm_only_questions BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5) the AI seller's own name («معاك محمد من ليلان ستور») ---------------------------------------------------------------------
ALTER TABLE chat.settings ADD COLUMN IF NOT EXISTS agent_name VARCHAR(30);

-- 6) one place that keeps every chat's timeline right, whatever code wrote the message (AI, team, answered questions, follow-ups) ---
--    customer message: remember when, reopen a closed chat, stop the follow-ups still waiting, and note that a follow-up brought them back;
--    AI / team message (not a follow-up): remember when, and schedule this quiet period's follow-up steps when the shop turned them on.
CREATE OR REPLACE FUNCTION chat.on_message_insert() RETURNS trigger AS $$
DECLARE
    c chat.conversations%ROWTYPE;
BEGIN
    SELECT * INTO c FROM chat.conversations WHERE id = NEW.conversation_id;
    IF NOT FOUND OR c.channel = 'TEST' THEN
        RETURN NEW;
    END IF;
    IF NEW.role = 'user' THEN
        UPDATE chat.conversations SET last_customer_at = NEW.created_at,
               status = 'OPEN', closed_at = CASE WHEN status = 'CLOSED' THEN NULL ELSE closed_at END
         WHERE id = NEW.conversation_id;
        UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'CUSTOMER_REPLIED'
         WHERE conversation_id = NEW.conversation_id AND status = 'SCHEDULED';
        UPDATE chat.auto_followups SET returned_at = NEW.created_at   -- the last follow-up before the customer came back
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

CREATE TRIGGER trg_chat_message_insert AFTER INSERT ON chat.messages FOR EACH ROW EXECUTE FUNCTION chat.on_message_insert();

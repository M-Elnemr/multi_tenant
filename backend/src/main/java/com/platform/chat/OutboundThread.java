package com.platform.chat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes a message the system sent on its own (order confirmation, reminder, cart reminder, comment private message) into the customer's chat, so the inbox thread shows it and the
 * database trigger can tag where the chat started (origin) when the customer answers. The conversation is found or created the same way an inbound message does it.
 */
@Component
public class OutboundThread {
    private final JdbcClient jdbc;

    public OutboundThread(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** kind: CONFIRM, CART, FOLLOWUP, BROADCAST or COMMENT_DM. Never throws: a log row must not break a send that already happened. */
    public void record(UUID tenantId, String channel, String externalId, String kind, String text) {
        try {
            if (externalId == null || externalId.isBlank() || text == null) return;
            String ext = "WHATSAPP".equals(channel) ? externalId.replaceAll("\\D", "") : externalId;
            UUID conv = jdbc.sql("""
                    INSERT INTO chat.conversations (tenant_id, channel, external_id) VALUES (:t, :c, :e)
                    ON CONFLICT (tenant_id, channel, external_id) DO UPDATE SET updated_at = now() RETURNING id
                    """).param("t", tenantId).param("c", channel).param("e", ext).query(UUID.class).single();
            jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, kind, by_owner) VALUES (:c, 'assistant', :x, :k, FALSE)")
                    .param("c", conv).param("x", text.length() > 4000 ? text.substring(0, 4000) : text).param("k", kind).update();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(OutboundThread.class).warn("Could not record the {} message in the chat: {}", kind, e.getMessage());
        }
    }
}

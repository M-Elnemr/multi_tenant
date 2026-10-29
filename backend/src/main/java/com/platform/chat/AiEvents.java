package com.platform.chat;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What happened in each AI reply, for the AI health page and the alerts: how long it took, which checks had to correct it, a switch to the backup model, an error. Owner test chats
 * and evaluations (the TEST channel) are not counted. Writing an event never fails a reply.
 */
@Component
public class AiEvents {
    private static final Logger log = LoggerFactory.getLogger(AiEvents.class);
    private final JdbcClient jdbc;

    public AiEvents(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(UUID tenantId, UUID conversationId, String channel, String model, String kind, String detail, Long ms) {
        if (tenantId == null || "TEST".equals(channel)) return;
        try {
            jdbc.sql("INSERT INTO chat.ai_events (tenant_id, conversation_id, channel, model, kind, detail, ms) VALUES (:t, :c, :ch, :m, :k, :d, :ms)")
                    .param("t", tenantId).param("c", conversationId).param("ch", channel).param("m", model).param("k", kind.length() > 40 ? kind.substring(0, 40) : kind)
                    .param("d", detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail).param("ms", ms).update();
        } catch (RuntimeException e) {
            log.debug("AI event not recorded", e);
        }
    }

    /** Events are kept for 60 days. */
    @Scheduled(cron = "0 40 4 * * *", zone = "Africa/Cairo")
    public void purge() { jdbc.sql("DELETE FROM chat.ai_events WHERE created_at < now() - interval '60 days'").update(); }
}

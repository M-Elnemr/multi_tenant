package com.platform.chat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The system events shown in a chat's timeline («AI paused by Mona», «Follow-up sent», «Closed»). */
@Component
public class ChatTimeline {
    private final JdbcClient jdbc;

    public ChatTimeline(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void event(UUID tenantId, UUID conversationId, String kind, UUID actor, String metaJson) {
        jdbc.sql("INSERT INTO chat.conversation_events (tenant_id, conversation_id, kind, actor_user_id, meta) VALUES (:t, :c, :k, :a, CAST(:m AS jsonb))")
                .param("t", tenantId).param("c", conversationId).param("k", kind).param("a", actor).param("m", metaJson).update();
    }

    /**
     * After a customer message: re-reads the customer's recent messages with {@link UnhappyRules} and sets or clears the unhappy tag (never against the owner: a tag the owner
     * set stays, and a tag the owner removed comes back only on new strong words after that).
     */
    public void judgeMood(UUID tenantId, UUID conversationId) {
        var st = jdbc.sql("SELECT unhappy, unhappy_source, unhappy_at FROM chat.conversations WHERE id = :c").param("c", conversationId).query().listOfRows().stream().findFirst().orElse(null);
        if (st == null || "OWNER".equals(st.get("unhappy_source"))) return;
        var msgs = jdbc.sql("SELECT content FROM (SELECT id, content FROM chat.messages WHERE conversation_id = :c AND role = 'user' ORDER BY id DESC LIMIT 6) x ORDER BY id").param("c", conversationId).query(String.class).list();
        var v = UnhappyRules.judge(msgs);
        boolean now = Boolean.TRUE.equals(st.get("unhappy"));
        if ("OWNER_CLEARED".equals(st.get("unhappy_source"))) {
            if (!(v.unhappy() && v.strong())) return;   // the owner said it is fine: only fresh strong words bring the tag back
        }
        if (v.unhappy() && !now) {
            jdbc.sql("UPDATE chat.conversations SET unhappy = TRUE, unhappy_reason = :r, unhappy_source = 'RULES', unhappy_at = now() WHERE id = :c").param("r", v.reason()).param("c", conversationId).update();
            event(tenantId, conversationId, "UNHAPPY_ON", null, "{\"source\":\"RULES\"}");
        } else if (v.unhappy()) {
            jdbc.sql("UPDATE chat.conversations SET unhappy_reason = :r WHERE id = :c").param("r", v.reason()).param("c", conversationId).update();
        }
    }
}

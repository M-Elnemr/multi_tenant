package com.platform.chat;

import com.platform.chat.MetaGraph.MetaException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Sends a broadcast message to one Messenger or Instagram contact through the shop's own page / account (Graph {@code me/messages}, messaging type RESPONSE, so only while the
 * platform window is open: the caller checks that). Text, or the picture first and the text after it, exactly like a product card. A message that goes out is also written into the
 * contact's conversation (role assistant, kind BROADCAST) so the inbox shows it and a reply is recognised as coming from the broadcast. Never throws.
 */
@Service
public class MetaBroadcastSender {
    private static final Logger log = LoggerFactory.getLogger(MetaBroadcastSender.class);

    /** ok = delivered to Meta; otherwise {@code error} says why (kept short, shown to the owner). */
    public record Outcome(boolean ok, String error) {
        static Outcome sent() { return new Outcome(true, null); }
        static Outcome failed(String why) { return new Outcome(false, why == null ? "FAILED" : why.length() > 190 ? why.substring(0, 190) : why); }
    }

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final TokenCrypto crypto;

    public MetaBroadcastSender(JdbcClient jdbc, MetaGraph graph, TokenCrypto crypto) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.crypto = crypto;
    }

    /** The shop's active channel token for the platform (FACEBOOK or INSTAGRAM), or null. */
    String token(UUID tenantId, String platform) {
        var enc = jdbc.sql("SELECT token_enc FROM chat.channels WHERE tenant_id = :t AND platform = :p AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", platform).query(String.class).optional();
        return enc.map(crypto::decrypt).orElse(null);
    }

    public Outcome send(UUID tenantId, String platform, String externalId, String text, String imageUrl) {
        try {
            if (!"FACEBOOK".equals(platform) && !"INSTAGRAM".equals(platform)) return Outcome.failed("UNSUPPORTED_PLATFORM");
            if (externalId == null || externalId.isBlank()) return Outcome.failed("NO_RECIPIENT");
            String token = token(tenantId, platform);
            if (token == null) return Outcome.failed("NO_CHANNEL");
            if (imageUrl != null && !imageUrl.isBlank()) {
                try {
                    graph.post("me/messages", token, Map.of("recipient", Map.of("id", externalId), "messaging_type", "RESPONSE",
                            "message", Map.of("attachment", Map.of("type", "image", "payload", Map.of("url", imageUrl, "is_reusable", false)))));
                } catch (MetaException e) {
                    log.debug("Broadcast picture not sent, the text goes alone: {}", e.getMessage());   // like a product card: the text is what matters
                }
            }
            for (String part : MetaChannelService.chunks(text, "INSTAGRAM".equals(platform) ? 900 : 1900))
                graph.post("me/messages", token, Map.of("recipient", Map.of("id", externalId), "messaging_type", "RESPONSE", "message", Map.of("text", part)));
            record(tenantId, platform, externalId, text);
            return Outcome.sent();
        } catch (MetaException e) {
            log.warn("Broadcast to {} failed: {}", platform, e.getMessage());
            return Outcome.failed(e.code() == 190 ? "TOKEN_EXPIRED" : e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Broadcast to {} failed: {}", platform, e.toString());
            return Outcome.failed(e.getMessage());
        }
    }

    /** Writes the message into the contact's conversation, when there is one. */
    void record(UUID tenantId, String platform, String externalId, String text) {
        try {
            jdbc.sql("""
                    INSERT INTO chat.messages (conversation_id, role, content, kind)
                    SELECT c.id, 'assistant', :x, 'BROADCAST' FROM chat.conversations c WHERE c.tenant_id = :t AND c.channel = :p AND c.external_id = :e
                    """).param("x", text).param("t", tenantId).param("p", platform).param("e", externalId).update();
        } catch (RuntimeException e) {
            log.warn("Could not record the broadcast message in the chat: {}", e.getMessage());
        }
    }
}

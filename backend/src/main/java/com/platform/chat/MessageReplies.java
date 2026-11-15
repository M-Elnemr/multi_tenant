package com.platform.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.shared.BusinessException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reply references of chat messages: validates them, stores them, and reads the quoted message back, even when it is far older than the history window.
 * Every read and write names the tenant AND the conversation, so a reference can never reach another shop's or another chat's message, whatever a client or a model sends.
 * Each lookup is one indexed read (the message's primary key, joined to its conversation for the tenant check); the whole chat is never loaded for it.
 */
@Component
public class MessageReplies {
    private static final Logger log = LoggerFactory.getLogger(MessageReplies.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int QUOTE_MAX = 600;

    /** A quoted message as the assistant and the screens see it. {@code messageId} is internal (never shown to a customer or a model); null when only a snapshot is left. */
    public record Quoted(Long messageId, String sender, String kind, String text, String mediaUrl, OffsetDateTime at, boolean fromSnapshot) {}

    private final JdbcClient jdbc;

    public MessageReplies(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String ONE = """
            SELECT m.id, m.role, m.by_owner, m.kind, m.content, m.media_url, m.created_at
            FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id
            WHERE m.id = :id AND m.conversation_id = :c AND c.tenant_id = :t
            """;

    /** The message {@code id} of THIS chat of THIS shop, or null when it does not exist or belongs to another chat or shop (the caller says nothing about which). */
    public Quoted load(UUID tenantId, UUID conversationId, long id) {
        return jdbc.sql(ONE).param("id", id).param("c", conversationId).param("t", tenantId).query((rs, n) -> quoted(rs.getLong("id"), rs.getString("role"), rs.getBoolean("by_owner"), rs.getString("kind"),
                rs.getString("content"), rs.getString("media_url"), rs.getObject("created_at", OffsetDateTime.class), false)).optional().orElse(null);
    }

    private static Quoted quoted(Long id, String role, boolean byOwner, String kind, String content, String mediaUrl, OffsetDateTime at, boolean snapshot) {
        String sender = "user".equals(role) ? "customer" : byOwner ? "owner" : "assistant";
        return new Quoted(id, sender, mediaUrl != null && !mediaUrl.isBlank() ? "image" : "text", clip(content, QUOTE_MAX), mediaUrl, at, snapshot);
    }

    /** The internal id of the message the platform's id {@code mid} names, in this chat of this shop; null when we hold no such message. */
    public Long idForMid(UUID tenantId, UUID conversationId, String mid) {
        if (mid == null || mid.isBlank()) return null;
        return jdbc.sql("SELECT message_id FROM chat.message_mids WHERE conversation_id = :c AND tenant_id = :t AND provider_mid = :m").param("c", conversationId).param("t", tenantId).param("m", mid.trim())
                .query(Long.class).optional().orElse(null);
    }

    /** The exact bubble of a message we sent in several bubbles (null when the id names a whole message or is unknown). */
    String partText(UUID tenantId, UUID conversationId, String mid) {
        if (mid == null || mid.isBlank()) return null;
        return jdbc.sql("SELECT part_text FROM chat.message_mids WHERE conversation_id = :c AND tenant_id = :t AND provider_mid = :m").param("c", conversationId).param("t", tenantId).param("m", mid.trim())
                .query(String.class).optional().orElse(null);
    }

    /**
     * Resolves {@code ref} to the stored message when it names one of this chat (by our id or the platform's), checking tenant and conversation, else to the platform's own quote.
     * A client-supplied {@code messageId} that is not of this chat is an error ({@code strict}) and never reveals whether it exists elsewhere.
     */
    public Quoted resolve(UUID tenantId, UUID conversationId, ReplyRef ref, boolean strict) {
        if (ref == null || ref.empty()) return null;
        Long id = null;
        if (ref.messageId() != null) {
            if (load(tenantId, conversationId, ref.messageId()) == null) {
                if (strict) throw BusinessException.badRequest("INVALID_REPLY_REFERENCE", "The message you are replying to is not available");
            } else id = ref.messageId();
        }
        if (id == null && ref.providerMid() != null) id = idForMid(tenantId, conversationId, ref.providerMid());
        if (id != null) {
            Quoted q = load(tenantId, conversationId, id);
            if (q != null) {
                String part = ref.providerMid() == null ? null : partText(tenantId, conversationId, ref.providerMid());   // the bubble the customer swiped, not the whole answer
                return part == null ? q : new Quoted(q.messageId(), q.sender(), q.kind(), clip(part, QUOTE_MAX), q.mediaUrl(), q.at(), false);
            }
        }
        if (ref.quote() != null) {
            var s = ref.quote();
            return new Quoted(null, s.sender() == null ? "shop" : s.sender(), s.kind() == null ? "text" : s.kind(), clip(s.text(), QUOTE_MAX), null, parse(s.at()), true);
        }
        return null;
    }

    /** Stores the reference on the message {@code messageId} of this chat: the id (when the message is held), the platform's id and a snapshot of what was quoted. A reference that cannot be resolved keeps only the platform's id. */
    public void attach(UUID tenantId, UUID conversationId, long messageId, ReplyRef ref, boolean strict) {
        Quoted q = resolve(tenantId, conversationId, ref, strict);
        if (q == null && (ref == null || ref.providerMid() == null)) return;
        jdbc.sql("UPDATE chat.messages SET reply_to_id = :r, reply_to_mid = :m, reply_quote = CAST(:q AS jsonb) WHERE id = :id AND conversation_id = :c")
                .param("r", q == null ? null : q.messageId()).param("m", ref.providerMid()).param("q", q == null ? null : snapshot(q, ref)).param("id", messageId).param("c", conversationId).update();
    }

    private static String snapshot(Quoted q, ReplyRef ref) {
        ObjectNode n = JSON.createObjectNode();
        n.put("sender", q.sender()).put("kind", q.kind()).put("text", q.text() == null ? "" : q.text()).put("hasMedia", q.mediaUrl() != null);
        if (q.at() != null) n.put("at", q.at().toString());
        n.put("source", q.fromSnapshot() ? (ref.quote() != null && ref.quote().source() != null ? ref.quote().source() : "platform") : "stored");
        return n.toString();
    }

    /** A quoted message together with the customer line that replied to it (null when the whole message is one line). */
    public record LineQuote(String line, Quoted quoted) {}

    /**
     * Stores every reference of a burst of customer lines on the message, each with ITS line and in order (the first also goes into the primary columns). A reference that cannot be
     * resolved and has no platform id is not stored. Nothing is invented: the quote of a reference is what was found or what the platform reported.
     */
    public void attachAll(UUID tenantId, UUID conversationId, long messageId, List<ReplyRef> refs) {
        if (refs == null || refs.isEmpty()) return;
        attach(tenantId, conversationId, messageId, refs.get(0), false);
        if (refs.size() < 2 && refs.get(0).line() == null) return;   // one plain reference: the primary columns say it all
        var arr = JSON.createArrayNode();
        for (ReplyRef r : refs) {
            Quoted q = resolve(tenantId, conversationId, r, false);
            if (q == null && r.providerMid() == null) continue;
            ObjectNode n = arr.addObject();
            n.put("line", r.line() == null ? "" : r.line());
            if (q != null && q.messageId() != null) n.put("id", q.messageId());
            if (r.providerMid() != null) n.put("mid", r.providerMid());
            if (q != null) {
                n.put("sender", q.sender()).put("kind", q.kind()).put("text", q.text() == null ? "" : q.text()).put("hasMedia", q.mediaUrl() != null);
                if (q.at() != null) n.put("at", q.at().toString());
                n.put("source", q.fromSnapshot() ? (r.quote() != null && r.quote().source() != null ? r.quote().source() : "platform") : "stored");
            }
        }
        if (arr.isEmpty()) return;
        jdbc.sql("UPDATE chat.messages SET reply_refs = CAST(:r AS jsonb) WHERE id = :id AND conversation_id = :c").param("r", arr.toString()).param("id", messageId).param("c", conversationId).update();
    }

    /** Every reference stored on the message, in the order of the customer's lines: the stored original when it still exists, else the snapshot. Empty when it replies to nothing. */
    public List<LineQuote> quotedAll(UUID tenantId, UUID conversationId, long messageId) {
        String json = jdbc.sql("SELECT m.reply_refs::text FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE m.id = :id AND m.conversation_id = :c AND c.tenant_id = :t")
                .param("id", messageId).param("c", conversationId).param("t", tenantId).query(String.class).optional().orElse(null);
        List<LineQuote> out = new ArrayList<>();
        if (json == null) return out;
        try {
            for (var n : JSON.readTree(json)) {
                Quoted live = n.hasNonNull("id") ? load(tenantId, conversationId, n.path("id").asLong()) : null;
                Quoted q = live != null ? live : n.hasNonNull("sender") ? new Quoted(null, n.path("sender").asText("shop"), n.path("kind").asText("text"), n.path("text").asText(""), null, parse(n.path("at").asText(null)), true) : null;
                if (q != null) out.add(new LineQuote(n.path("line").asText(""), q));
            }
        } catch (Exception e) { log.debug("Reply references unreadable: {}", e.toString()); }
        return out;
    }

    /** What the message {@code messageId} quotes, for the assistant: the stored original when it still exists, else the snapshot taken when the reply was written, else null (never guessed). */
    public Quoted quotedBy(UUID tenantId, UUID conversationId, long messageId) {
        var row = jdbc.sql("""
                SELECT m.reply_to_id, m.reply_to_mid, m.reply_quote::text AS q FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id
                WHERE m.id = :id AND m.conversation_id = :c AND c.tenant_id = :t
                """).param("id", messageId).param("c", conversationId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) return null;
        Quoted live = null;
        if (row.get("reply_to_id") instanceof Number n) live = load(tenantId, conversationId, n.longValue());
        if (live != null) {
            String part = row.get("reply_to_mid") == null ? null : partText(tenantId, conversationId, String.valueOf(row.get("reply_to_mid")));
            return part == null ? live : new Quoted(live.messageId(), live.sender(), live.kind(), clip(part, QUOTE_MAX), live.mediaUrl(), live.at(), false);
        }
        if (row.get("q") == null) return null;
        try {
            var n = JSON.readTree(String.valueOf(row.get("q")));
            return new Quoted(null, n.path("sender").asText("shop"), n.path("kind").asText("text"), n.path("text").asText(""), null, parse(n.path("at").asText(null)), true);
        } catch (Exception e) { log.debug("Reply snapshot unreadable: {}", e.toString()); return null; }
    }

    /** The messages next to the quoted one (a few before, a few after), so the quoted line is read in its own conversation, not alone. Two bounded indexed reads. */
    public List<Quoted> around(UUID tenantId, UUID conversationId, long id, int before, int after) {
        List<Quoted> out = new ArrayList<>();
        String sql = """
                SELECT m.id, m.role, m.by_owner, m.kind, m.content, m.media_url, m.created_at FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id
                WHERE m.conversation_id = :c AND c.tenant_id = :t AND m.id %s :id ORDER BY m.id %s LIMIT :n
                """;
        List<Quoted> prev = new ArrayList<>(jdbc.sql(sql.formatted("<", "DESC")).param("c", conversationId).param("t", tenantId).param("id", id).param("n", before).query(this::row).list());
        java.util.Collections.reverse(prev);
        out.addAll(prev);
        out.addAll(jdbc.sql(sql.formatted(">", "ASC")).param("c", conversationId).param("t", tenantId).param("id", id).param("n", after).query(this::row).list());
        return out;
    }

    private Quoted row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return quoted(rs.getLong("id"), rs.getString("role"), rs.getBoolean("by_owner"), rs.getString("kind"), rs.getString("content"), rs.getString("media_url"), rs.getObject("created_at", OffsetDateTime.class), false);
    }

    /** Remembers the platform's id of a message of this chat (and, for one of several bubbles, its exact text) so a later native reply to it is found. Never throws: a send is not undone by it. */
    public void recordMid(UUID tenantId, UUID conversationId, long messageId, String mid, String partText) {
        if (mid == null || mid.isBlank() || messageId <= 0) return;
        try {
            jdbc.sql("INSERT INTO chat.message_mids (tenant_id, conversation_id, provider_mid, message_id, part_text) VALUES (:t, :c, :m, :id, :p) ON CONFLICT DO NOTHING")
                    .param("t", tenantId).param("c", conversationId).param("m", mid.trim()).param("id", messageId).param("p", partText == null ? null : clip(partText, 4000)).update();
        } catch (RuntimeException e) { log.debug("Platform message id not saved: {}", e.toString()); }
    }

    /** The reply data of messages for the screens: for each message that quotes one, who said what (from the stored original, else the snapshot). Keyed by message id; one query for the whole page. */
    public Map<Long, Map<String, Object>> forScreens(UUID tenantId, UUID conversationId, List<Long> messageIds) {
        Map<Long, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        if (messageIds.isEmpty()) return out;
        for (var r : jdbc.sql("""
                SELECT m.id, m.reply_to_id, q.role AS q_role, q.by_owner AS q_owner, q.content AS q_content, q.media_url AS q_media, m.reply_quote::text AS snap
                FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id LEFT JOIN chat.messages q ON q.id = m.reply_to_id AND q.conversation_id = m.conversation_id
                WHERE m.conversation_id = :c AND c.tenant_id = :t AND m.id = ANY(:ids) AND (m.reply_to_id IS NOT NULL OR m.reply_quote IS NOT NULL OR m.reply_to_mid IS NOT NULL)
                """).param("c", conversationId).param("t", tenantId).param("ids", messageIds.toArray(Long[]::new)).query().listOfRows()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            if (r.get("q_content") != null) {
                m.put("id", r.get("reply_to_id"));
                m.put("sender", "user".equals(r.get("q_role")) ? "customer" : Boolean.TRUE.equals(r.get("q_owner")) ? "owner" : "assistant");
                m.put("text", clip(String.valueOf(r.get("q_content")), QUOTE_MAX));
                m.put("hasMedia", r.get("q_media") != null);
                m.put("available", true);
            } else if (r.get("snap") != null) {
                try {
                    var n = JSON.readTree(String.valueOf(r.get("snap")));
                    m.put("id", null); m.put("sender", n.path("sender").asText("shop")); m.put("text", n.path("text").asText("")); m.put("hasMedia", n.path("hasMedia").asBoolean(false)); m.put("available", false);
                } catch (Exception e) { m.put("available", false); }
            } else { m.put("available", false); }
            out.put(((Number) r.get("id")).longValue(), m);
        }
        return out;
    }

    static String clip(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    private static OffsetDateTime parse(String s) {
        try { return s == null || s.isBlank() ? null : OffsetDateTime.parse(s); } catch (RuntimeException e) { return null; }
    }
}

package com.platform.chat;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The shop's unified inbox: every customer chat from the website, WhatsApp, Messenger and Instagram in one list, with what each one needs right now.
 * <ul>
 * <li>Statuses are worked out from the chat's own timestamps when it is read, so they are always right without a job: NEEDS_REPLY (the customer wrote last), WAITING
 * (the shop or AI answered and the customer has been quiet 30 minutes or more), UNHAPPY (word rules or the owner), TAKEOVER (a person handles it), OPEN / CLOSED.</li>
 * <li>Filters: OR between the values of one group, AND between groups.</li>
 * <li>Taking a chat over silences the AI in that chat only (never the whole shop) until the owner resumes it; resuming sends nothing by itself.</li>
 * </ul>
 */
@Service
public class ChatInboxService {
    static final int PAUSE_HOURS = 2;
    static final int PAGE = 30;
    private static final Set<String> STATUSES = Set.of("NEEDS_REPLY", "WAITING", "UNHAPPY", "TAKEOVER", "OPEN", "CLOSED");
    private static final Set<String> CHANNELS = Set.of("WEB", "WHATSAPP", "FACEBOOK", "INSTAGRAM");
    private static final Set<String> CUSTOMERS = Set.of("NEW", "RETURNING", "WITH_ORDERS", "NO_ORDERS");

    static final String NEEDS = "(c.status = 'OPEN' AND c.last_customer_at IS NOT NULL AND c.last_customer_at > coalesce(c.last_reply_at, '-infinity'::timestamptz))";
    static final String WAITING = "(c.status = 'OPEN' AND c.last_reply_at IS NOT NULL AND c.last_reply_at >= coalesce(c.last_customer_at, '-infinity'::timestamptz) AND c.last_reply_at <= now() - interval '30 minutes')";
    static final String PAUSED = "(c.takeover OR coalesce(c.ai_paused_until > now(), FALSE))";
    static final String UNREAD = "(c.last_customer_at IS NOT NULL AND c.last_customer_at > coalesce(c.owner_read_at, '-infinity'::timestamptz))";

    private final JdbcClient jdbc;
    private final ChatDelivery delivery;
    private final com.platform.audit.AuditService audit;
    private final ChannelWindow window;
    private final ChatCustomer customers;
    private final ChatTimeline timeline;
    private final ChatService chat;
    private final AiProvider ai;
    private final AiUsageService aiUsage;
    private final com.platform.core.auth.LoginThrottle throttle;
    private final com.platform.open.OpenUrls urls;

    public ChatInboxService(JdbcClient jdbc, ChatDelivery delivery, com.platform.audit.AuditService audit, ChannelWindow window, ChatCustomer customers, ChatTimeline timeline,
                            @org.springframework.context.annotation.Lazy ChatService chat, AiProvider ai, AiUsageService aiUsage, com.platform.core.auth.LoginThrottle throttle, com.platform.open.OpenUrls urls) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.audit = audit;
        this.window = window;
        this.customers = customers;
        this.timeline = timeline;
        this.chat = chat;
        this.ai = ai;
        this.aiUsage = aiUsage;
        this.throttle = throttle;
        this.urls = urls;
    }

    // ---- list ---------------------------------------------------------------------------------------------------------

    /** What the list can be narrowed by; null / empty means "any". */
    public record Filter(Collection<String> status, Collection<String> ai, Collection<String> channel, String since, LocalDate from, LocalDate to, Collection<String> customer,
                         String q, String sort, Integer cursor, Integer limit, Boolean needsYou) {}

    /** The old list (needs-you first, 50 newest), kept for the mobile app. */
    public List<Map<String, Object>> list(UUID tenantId, boolean needsYou) { return list(tenantId, needsYou, null, null); }

    public List<Map<String, Object>> list(UUID tenantId, boolean needsYou, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) search(tenantId, new Filter(null, null, null, null, from, to, null, null, needsYou ? "priority" : "newest", 0, 50, needsYou)).get("data");
        return data;
    }

    /** One page of the inbox: {data, total, nextCursor}. */
    public Map<String, Object> search(UUID tenantId, Filter f) {
        com.platform.shared.DateRange.check(f.from(), f.to());
        List<String> where = new ArrayList<>(List.of("c.tenant_id = :t", "c.channel <> 'TEST'", "EXISTS (SELECT 1 FROM chat.messages m WHERE m.conversation_id = c.id)"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("t", tenantId);
        if (Boolean.TRUE.equals(f.needsYou())) where.add("(c.needs_human OR " + NEEDS + " OR c.unhappy)");
        List<String> ors = new ArrayList<>();
        for (String s : clean(f.status(), STATUSES)) ors.add(switch (s) {
            case "NEEDS_REPLY" -> NEEDS;
            case "WAITING" -> WAITING;
            case "UNHAPPY" -> "c.unhappy";
            case "TAKEOVER" -> "c.takeover";
            case "OPEN" -> "c.status = 'OPEN'";
            default -> "c.status = 'CLOSED'";
        });
        if (!ors.isEmpty()) where.add("(" + String.join(" OR ", ors) + ")");
        var aiSet = clean(f.ai(), Set.of("ACTIVE", "PAUSED"));
        if (aiSet.size() == 1) {
            List<String> off = new ArrayList<>();
            for (String ch : CHANNELS) if (!chat.enabled(tenantId, ch)) off.add(ch);
            String offSql = off.isEmpty() ? "FALSE" : "c.channel IN (:offCh)";
            if (!off.isEmpty()) p.put("offCh", off);
            where.add(aiSet.contains("PAUSED") ? "(" + PAUSED + " OR " + offSql + ")" : "(NOT " + PAUSED + " AND NOT " + offSql + ")");
        }
        var chSet = clean(f.channel(), CHANNELS);
        if (!chSet.isEmpty()) { where.add("c.channel IN (:ch)"); p.put("ch", chSet); }
        if (f.since() != null && !f.since().isBlank()) where.add(switch (f.since()) {
            case "30m" -> "c.updated_at >= now() - interval '30 minutes'";
            case "1h" -> "c.updated_at >= now() - interval '1 hour'";
            case "today" -> "c.updated_at >= date_trunc('day', now() AT TIME ZONE 'Africa/Cairo') AT TIME ZONE 'Africa/Cairo'";
            case "7d" -> "c.updated_at >= now() - interval '7 days'";
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown time filter");
        });
        if (f.from() != null || f.to() != null) { where.add(com.platform.shared.DateRange.sql("c.updated_at", "t")); p.put("from", f.from()); p.put("to", f.to()); }
        var cuSet = clean(f.customer(), CUSTOMERS);
        if (!cuSet.isEmpty()) {
            List<String> cors = new ArrayList<>();
            for (String s : cuSet) cors.add(switch (s) {
                case "WITH_ORDERS" -> "coalesce(oc.n, 0) > 0";
                case "NO_ORDERS" -> "coalesce(oc.n, 0) = 0";
                case "NEW" -> "(coalesce(oc.n, 0) = 0 AND c.created_at >= now() - interval '7 days')";
                default -> "(coalesce(oc.n, 0) > 0 OR c.created_at < now() - interval '7 days')";
            });
            where.add("(" + String.join(" OR ", cors) + ")");
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            if (q.length() > 80) throw BusinessException.badRequest("VALIDATION_ERROR", "The search is too long");
            p.put("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            String digits = q.replaceAll("\\D", "");
            p.put("qd", digits.length() >= 4 ? "%" + digits + "%" : "##none##");
            where.add("(c.display_name ILIKE :q OR cu.name ILIKE :q OR cu.phone LIKE :qd OR c.external_id LIKE :qd OR c.contact_phone LIKE :qd"
                    + " OR EXISTS (SELECT 1 FROM chat.messages mq WHERE mq.conversation_id = c.id AND mq.created_at > now() - interval '90 days' AND mq.content ILIKE :q))");
        }
        String from = """
                FROM chat.conversations c
                LEFT JOIN LATERAL (SELECT %s AS cid) cl ON TRUE
                LEFT JOIN commerce.customers cu ON cu.id = cl.cid
                LEFT JOIN LATERAL (SELECT count(*) AS n FROM commerce.orders o WHERE o.tenant_id = c.tenant_id AND cl.cid IS NOT NULL AND o.customer_id = cl.cid) oc ON TRUE
                """.formatted(ChatCustomer.SQL);
        String w = " WHERE " + String.join(" AND ", where);
        String order = switch (f.sort() == null ? "newest" : f.sort()) {
            case "oldest" -> "c.updated_at ASC, c.id";
            case "unread" -> UNREAD + " DESC, c.updated_at DESC, c.id";
            case "priority" -> "c.unhappy DESC, c.needs_human DESC, " + NEEDS + " DESC, c.last_customer_at ASC NULLS LAST, c.id";
            case "newest" -> "c.updated_at DESC, c.id";
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown sort");
        };
        int limit = Math.max(1, Math.min(f.limit() == null ? PAGE : f.limit(), 100)), offset = Math.max(0, f.cursor() == null ? 0 : f.cursor());
        var countQ = jdbc.sql("SELECT count(*) " + from + w);
        for (var e : p.entrySet()) countQ = countQ.param(e.getKey(), e.getValue());
        long total = countQ.query(Long.class).single();
        var rowsQ = jdbc.sql("""
                SELECT c.*, cl.cid AS customer_id, cu.name AS customer_name, cu.phone AS customer_phone_db, coalesce(oc.n, 0) AS orders_count,
                       lm.content AS last_message, lm.role AS last_role, lm.by_owner AS last_by_owner, lm.kind AS last_kind, lm.media_url AS last_media,
                       %s AS needs_reply, %s AS waiting, %s AS paused, %s AS unread,
                       (SELECT count(*) FROM chat.messages mu WHERE mu.conversation_id = c.id AND mu.role = 'user' AND mu.created_at > coalesce(c.owner_read_at, '-infinity'::timestamptz)) AS unread_count
                """.formatted(NEEDS, WAITING, PAUSED, UNREAD) + from
                + " LEFT JOIN LATERAL (SELECT content, role, by_owner, kind, media_url FROM chat.messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE"
                + w + " ORDER BY " + order + " LIMIT :lim OFFSET :off");
        for (var e : p.entrySet()) rowsQ = rowsQ.param(e.getKey(), e.getValue());
        var rows = rowsQ.param("lim", limit).param("off", offset).query().listOfRows();
        Map<String, Boolean> on = new LinkedHashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        for (var r : rows) data.add(view(tenantId, r, on));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", total);
        out.put("nextCursor", offset + rows.size() < total ? offset + rows.size() : null);
        return out;
    }

    /** Counts for the menu badge and the inbox tabs. {@code count} stays the old "needs you" number for the mobile app. */
    public Map<String, Object> counts(UUID tenantId) {
        var r = jdbc.sql("SELECT count(*) FILTER (WHERE c.needs_human) AS needs_human, count(*) FILTER (WHERE " + NEEDS + ") AS needs_reply, count(*) FILTER (WHERE " + UNREAD + ") AS unread,"
                + " count(*) FILTER (WHERE c.unhappy AND c.status = 'OPEN') AS unhappy, count(*) FILTER (WHERE " + WAITING + ") AS waiting"
                + " FROM chat.conversations c WHERE c.tenant_id = :t AND c.channel <> 'TEST' AND (c.imported_at IS NULL OR EXISTS (SELECT 1 FROM chat.messages m WHERE m.conversation_id = c.id))").param("t", tenantId).query().singleRow();
        long comments = jdbc.sql("SELECT count(*) FROM chat.social_comments WHERE tenant_id = :t AND status = 'UNREPLIED'").param("t", tenantId).query(Long.class).single();
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        m.put("count", ((Number) r.get("needs_human")).longValue());
        m.put("unrepliedComments", comments);
        return m;
    }

    public long needsYouCount(UUID tenantId) { return ((Number) counts(tenantId).get("count")).longValue(); }

    // ---- one chat -------------------------------------------------------------------------------------------------------

    /** One chat: its messages (60 per page, older pages with {@code before}), system events and notes in time order, the channel window, the customer and follow-ups. */
    public Map<String, Object> get(UUID tenantId, UUID id) { return get(tenantId, id, null, true); }

    public Map<String, Object> get(UUID tenantId, UUID id, Long before, boolean markRead) {
        var r = row(tenantId, id);
        if (markRead && before == null) jdbc.sql("UPDATE chat.conversations SET owner_read_at = now() WHERE id = :c").param("c", id).update();
        Map<String, Object> out = view(tenantId, r, new LinkedHashMap<>());
        var msgs = new ArrayList<>(Rows.camel(jdbc.sql("SELECT id, role, content, by_owner, kind, media_url, created_at FROM chat.messages WHERE conversation_id = :c AND (CAST(:b AS bigint) IS NULL OR id < :b) ORDER BY id DESC LIMIT 61")
                .param("c", id).param("b", before).query().listOfRows()));
        boolean more = msgs.size() > 60;
        if (more) msgs.remove(msgs.size() - 1);
        java.util.Collections.reverse(msgs);
        out.put("messages", msgs);
        out.put("hasOlder", more);
        Object oldest = msgs.isEmpty() ? null : msgs.get(0).get("createdAt");
        if (oldest instanceof Instant i) oldest = java.sql.Timestamp.from(i);
        else if (oldest instanceof java.time.OffsetDateTime o) oldest = java.sql.Timestamp.from(o.toInstant());
        out.put("events", Rows.camel(jdbc.sql("""
                SELECT e.id, e.kind, e.meta::text AS meta, e.created_at, trim(concat(u.first_name, ' ', u.last_name)) AS actor
                FROM chat.conversation_events e LEFT JOIN core.users u ON u.id = e.actor_user_id
                WHERE e.conversation_id = :c AND (CAST(:o AS timestamptz) IS NULL OR e.created_at >= :o OR NOT :more) ORDER BY e.created_at
                """).param("c", id).param("o", oldest).param("more", more).query().listOfRows()));
        out.put("notes", notes(tenantId, id));
        Instant lastCustomer = r.get("last_customer_at") instanceof java.sql.Timestamp ts ? ts.toInstant() : r.get("last_customer_at") instanceof java.time.OffsetDateTime o ? o.toInstant() : null;
        out.put("window", window.of(tenantId, (String) r.get("channel"), (String) r.get("external_id"), lastCustomer));
        out.put("followUps", Rows.camel(jdbc.sql("SELECT id, step_position, due_at, status, reason, body, sent_at FROM chat.auto_followups WHERE conversation_id = :c ORDER BY anchor_customer_at DESC, step_position LIMIT 10").param("c", id).query().listOfRows()));
        out.put("customer", out.get("customerId") == null ? null : customerSummary(tenantId, (UUID) out.get("customerId")));
        out.put("channelName", channelName(tenantId, (String) r.get("channel")));
        return out;
    }

    /** The customer card of the side panel (null fields are left out by the screen): contact, tags, notes, orders and the other chats of this customer. */
    public Map<String, Object> customerSummary(UUID tenantId, UUID customerId) {
        var c = jdbc.sql("""
                SELECT cu.id, cu.customer_number, cu.name, cu.phone, cu.email, cu.gender, cu.notes, cu.created_at, cu.whatsapp_number, cu.broadcast_opt_out_at, cu.address_json::text AS address,
                       (SELECT count(*) FROM commerce.orders o WHERE o.customer_id = cu.id) AS orders_count,
                       (SELECT coalesce(sum(o.total_minor), 0) FROM commerce.orders o WHERE o.customer_id = cu.id AND o.status NOT IN ('CANCELLED')) AS total_spent_minor,
                       (SELECT max(o.created_at) FROM commerce.orders o WHERE o.customer_id = cu.id) AS last_order_at
                FROM commerce.customers cu WHERE cu.id = :c AND cu.tenant_id = :t
                """).param("c", customerId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(c));
        m.put("address", Rows.json(c.get("address")));
        m.put("tags", jdbc.sql("SELECT tag FROM commerce.customer_tags WHERE customer_id = :c ORDER BY created_at").param("c", customerId).query(String.class).list());
        m.put("orders", Rows.camel(jdbc.sql("SELECT id, order_number, status, total_minor, currency, created_at FROM commerce.orders WHERE customer_id = :c ORDER BY created_at DESC LIMIT 5").param("c", customerId).query().listOfRows()));
        return m;
    }

    // ---- actions --------------------------------------------------------------------------------------------------------

    /**
     * The owner's own message: refused (409 WINDOW_CLOSED) when the channel does not allow it right now; else saved, sent on the customer's channel, and the AI stays quiet in
     * this chat for two hours (or until resumed, when the chat was taken over). {name} and {shop} in the text are filled in.
     */
    public Map<String, Object> reply(UUID tenantId, UUID actor, UUID id, String text) { return reply(tenantId, actor, id, text, null); }

    public Map<String, Object> reply(UUID tenantId, UUID actor, UUID id, String text, UUID imageFileId) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() && imageFileId == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the message");
        if (t.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message is too long (up to 2000 characters)");
        var r = row(tenantId, id);
        String channel = (String) r.get("channel"), ext = (String) r.get("external_id");
        Instant lastCustomer = r.get("last_customer_at") instanceof java.sql.Timestamp ts ? ts.toInstant() : null;
        var w = window.of(tenantId, channel, ext, lastCustomer);
        if (!Boolean.TRUE.equals(w.get("canSend")))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "WINDOW_CLOSED", "This channel does not allow a message to this customer right now (the 24-hour window is closed or the channel is not connected)");
        t = fill(tenantId, r, t);
        String imageUrl = imageUrl(tenantId, imageFileId);
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, by_owner, media_url) VALUES (:c, 'assistant', :x, TRUE, :u)").param("c", id).param("x", t).param("u", imageUrl).update();
        jdbc.sql("UPDATE chat.conversations SET updated_at = now(), needs_human = FALSE, owner_read_at = now(), ai_paused_until = CASE WHEN takeover THEN ai_paused_until ELSE now() + make_interval(hours => :h) END WHERE id = :c")
                .param("h", PAUSE_HOURS).param("c", id).update();
        var sent = delivery.send(tenantId, channel, ext, t, List.of(), imageUrl);
        audit.record(actor, tenantId, "CHAT_OWNER_REPLY", "conversation", id, "{\"channel\":\"" + channel + "\"}");
        Map<String, Object> out = get(tenantId, id);
        out.put("delivery", sent.delivery());
        out.put("deliveryNote", sent.note());
        return out;
    }

    /** A person handles this chat: the AI stays quiet in it (this chat only) until the owner resumes it; follow-ups waiting for it are cancelled. */
    public Map<String, Object> takeover(UUID tenantId, UUID actor, UUID id) {
        row(tenantId, id);
        int n = jdbc.sql("UPDATE chat.conversations SET takeover = TRUE, takeover_by = :a, takeover_at = now(), needs_human = FALSE WHERE id = :c AND NOT takeover").param("a", actor).param("c", id).update();
        if (n > 0) {
            jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'TAKEOVER' WHERE conversation_id = :c AND status = 'SCHEDULED'").param("c", id).update();
            timeline.event(tenantId, id, "TAKEOVER", actor, null);
            audit.record(actor, tenantId, "CHAT_TAKEOVER", "conversation", id, null);
        }
        return get(tenantId, id);
    }

    /** The AI answers this chat again (from the customer's next message; nothing is sent now). */
    public Map<String, Object> resume(UUID tenantId, UUID actor, UUID id) {
        row(tenantId, id);
        jdbc.sql("UPDATE chat.conversations SET takeover = FALSE, takeover_by = NULL, takeover_at = NULL, ai_paused_until = NULL, needs_human = FALSE WHERE id = :c").param("c", id).update();
        timeline.event(tenantId, id, "RESUME", actor, null);
        audit.record(actor, tenantId, "CHAT_AI_RESUMED", "conversation", id, null);
        return get(tenantId, id);
    }

    /** The team takes the chat for {@code hours} (1 to 24): the AI stays quiet in it meanwhile (kept for the mobile app). */
    public Map<String, Object> pause(UUID tenantId, UUID actor, UUID id, Integer hours) {
        row(tenantId, id);
        int h = hours == null ? PAUSE_HOURS : Math.max(1, Math.min(24, hours));
        jdbc.sql("UPDATE chat.conversations SET ai_paused_until = now() + make_interval(hours => :h) WHERE id = :c").param("h", h).param("c", id).update();
        jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'PAUSED' WHERE conversation_id = :c AND status = 'SCHEDULED'").param("c", id).update();
        timeline.event(tenantId, id, "PAUSED", actor, "{\"hours\":" + h + "}");
        audit.record(actor, tenantId, "CHAT_AI_PAUSED", "conversation", id, "{\"hours\":" + h + "}");
        return get(tenantId, id);
    }

    public Map<String, Object> close(UUID tenantId, UUID actor, UUID id) {
        row(tenantId, id);
        int n = jdbc.sql("UPDATE chat.conversations SET status = 'CLOSED', closed_at = now(), needs_human = FALSE WHERE id = :c AND status = 'OPEN'").param("c", id).update();
        if (n > 0) {
            jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'CLOSED' WHERE conversation_id = :c AND status = 'SCHEDULED'").param("c", id).update();
            timeline.event(tenantId, id, "CLOSED", actor, null);
        }
        return get(tenantId, id);
    }

    public Map<String, Object> reopen(UUID tenantId, UUID actor, UUID id) {
        row(tenantId, id);
        if (jdbc.sql("UPDATE chat.conversations SET status = 'OPEN', closed_at = NULL WHERE id = :c AND status = 'CLOSED'").param("c", id).update() > 0) timeline.event(tenantId, id, "REOPENED", actor, null);
        return get(tenantId, id);
    }

    public void markRead(UUID tenantId, UUID id, boolean read) {
        row(tenantId, id);
        jdbc.sql(read ? "UPDATE chat.conversations SET owner_read_at = now() WHERE id = :c" : "UPDATE chat.conversations SET owner_read_at = last_customer_at - interval '1 second' WHERE id = :c").param("c", id).update();
    }

    /** The owner marks the customer unhappy, or says they are fine (then the word rules do not put the tag back unless new strong words come). */
    public Map<String, Object> unhappy(UUID tenantId, UUID actor, UUID id, boolean on) {
        row(tenantId, id);
        if (on) jdbc.sql("UPDATE chat.conversations SET unhappy = TRUE, unhappy_source = 'OWNER', unhappy_reason = NULL, unhappy_at = now() WHERE id = :c").param("c", id).update();
        else jdbc.sql("UPDATE chat.conversations SET unhappy = FALSE, unhappy_source = 'OWNER_CLEARED', unhappy_at = now() WHERE id = :c").param("c", id).update();
        timeline.event(tenantId, id, on ? "UNHAPPY_ON" : "UNHAPPY_OFF", actor, "{\"source\":\"OWNER\"}");
        return get(tenantId, id, null, false);
    }

    /** The team looked and there is nothing to do: the chat leaves the "needs you" list. */
    public Map<String, Object> done(UUID tenantId, UUID id) {
        row(tenantId, id);
        jdbc.sql("UPDATE chat.conversations SET needs_human = FALSE WHERE id = :c").param("c", id).update();
        return get(tenantId, id);
    }

    /** The same action on many chats at once: read, unread, close, takeover, resume. Returns how many were changed. */
    public int bulk(UUID tenantId, UUID actor, List<UUID> ids, String action) {
        if (ids == null || ids.isEmpty() || ids.size() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose 1 to 200 chats");
        int n = 0;
        for (UUID id : ids) {
            try {
                switch (action == null ? "" : action) {
                    case "read" -> markRead(tenantId, id, true);
                    case "unread" -> markRead(tenantId, id, false);
                    case "close" -> close(tenantId, actor, id);
                    case "takeover" -> takeover(tenantId, actor, id);
                    case "resume" -> resume(tenantId, actor, id);
                    default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown action");
                }
                n++;
            } catch (BusinessException e) {
                if ("VALIDATION_ERROR".equals(e.getCode())) throw e;   // not found chats are skipped
            }
        }
        return n;
    }

    // ---- internal notes -------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> notes(UUID tenantId, UUID id) {
        return Rows.camel(jdbc.sql("""
                SELECT n.id, n.body, n.created_at, n.author_user_id, trim(concat(u.first_name, ' ', u.last_name)) AS author
                FROM chat.conversation_notes n LEFT JOIN core.users u ON u.id = n.author_user_id WHERE n.conversation_id = :c AND n.tenant_id = :t ORDER BY n.created_at
                """).param("c", id).param("t", tenantId).query().listOfRows());
    }

    public List<Map<String, Object>> addNote(UUID tenantId, UUID actor, UUID id, String body) {
        row(tenantId, id);
        String b = body == null ? "" : body.trim();
        if (b.isEmpty() || b.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "Write a note (up to 2000 characters)");
        jdbc.sql("INSERT INTO chat.conversation_notes (tenant_id, conversation_id, author_user_id, body) VALUES (:t, :c, :a, :b)").param("t", tenantId).param("c", id).param("a", actor).param("b", b).update();
        return notes(tenantId, id);
    }

    public List<Map<String, Object>> deleteNote(UUID tenantId, UUID id, long noteId) {
        row(tenantId, id);
        jdbc.sql("DELETE FROM chat.conversation_notes WHERE id = :n AND conversation_id = :c AND tenant_id = :t").param("n", noteId).param("c", id).param("t", tenantId).update();
        return notes(tenantId, id);
    }

    // ---- saved replies --------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> savedReplies(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, title, body FROM chat.saved_replies WHERE tenant_id = :t ORDER BY title").param("t", tenantId).query().listOfRows());
    }

    public Map<String, Object> saveReply(UUID tenantId, UUID id, String title, String body) {
        String ti = title == null ? "" : title.trim(), b = body == null ? "" : body.trim();
        if (ti.isEmpty() || ti.length() > 80) throw BusinessException.badRequest("VALIDATION_ERROR", "Give the reply a short title (up to 80 characters)");
        if (b.isEmpty() || b.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the reply (up to 2000 characters)");
        if (id == null) {
            if (jdbc.sql("SELECT count(*) FROM chat.saved_replies WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single() >= 100) throw BusinessException.conflict("LIMIT_REACHED", "Up to 100 saved replies");
            id = jdbc.sql("INSERT INTO chat.saved_replies (tenant_id, title, body) VALUES (:t, :ti, :b) RETURNING id").param("t", tenantId).param("ti", ti).param("b", b).query(UUID.class).single();
        } else if (jdbc.sql("UPDATE chat.saved_replies SET title = :ti, body = :b WHERE id = :i AND tenant_id = :t").param("ti", ti).param("b", b).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Saved reply not found");
        return Map.of("id", id, "title", ti, "body", b);
    }

    public void deleteReply(UUID tenantId, UUID id) { jdbc.sql("DELETE FROM chat.saved_replies WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update(); }

    // ---- AI Assist (never sends anything) ---------------------------------------------------------------------------------

    static final Set<String> ASSIST = Set.of("SUGGEST", "REWRITE", "FRIENDLIER", "SHORTER", "SELL", "SUMMARY", "NEEDS", "NEXT_ACTION");

    /** A draft or an analysis for the owner to read; the owner always reviews and sends it themself. */
    public Map<String, Object> assist(UUID tenantId, UUID id, String action, String draft) {
        if (action == null || !ASSIST.contains(action)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown AI Assist action");
        var r = row(tenantId, id);
        boolean needsDraft = Set.of("REWRITE", "FRIENDLIER", "SHORTER", "SELL").contains(action);
        String d = draft == null ? "" : draft.trim();
        if (needsDraft && d.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Write a draft first");
        if (d.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "The draft is too long");
        throttle.check("inbox-assist|" + tenantId, 40, java.time.Duration.ofHours(1));
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        StringBuilder convo = new StringBuilder();
        for (var m : jdbc.sql("SELECT role, by_owner, content FROM (SELECT id, role, by_owner, content FROM chat.messages WHERE conversation_id = :c ORDER BY id DESC LIMIT 30) x ORDER BY id").param("c", id).query().listOfRows())
            convo.append("user".equals(m.get("role")) ? "CUSTOMER: " : Boolean.TRUE.equals(m.get("by_owner")) ? "SHOP TEAM: " : "AI SELLER: ").append(m.get("content")).append('\n');
        String task = switch (action) {
            case "SUGGEST" -> "Write the best next reply from the shop to the customer.";
            case "REWRITE" -> "Rewrite the shop's draft so it reads clearly and naturally. Keep its meaning, prices and promises exactly.";
            case "FRIENDLIER" -> "Rewrite the shop's draft warmer and friendlier. Keep its meaning, prices and promises exactly.";
            case "SHORTER" -> "Rewrite the shop's draft much shorter (one or two short sentences). Keep its meaning, prices and promises exactly.";
            case "SELL" -> "Rewrite the shop's draft so it sells better: answer the need, mention the value, end with one easy question that moves to the order. Keep prices and promises exactly; add none.";
            case "SUMMARY" -> "Summarise this conversation for the shop owner in 2-4 short bullet points (what the customer wants, what was agreed, what is open).";
            case "NEEDS" -> "List what this customer needs or wants, in 1-4 short bullet points, for the shop owner.";
            default -> "Suggest the single best next action for the shop owner in this conversation (one or two sentences), e.g. send a photo, confirm the order, offer the size chart, call them.";
        };
        boolean forOwner = Set.of("SUMMARY", "NEEDS", "NEXT_ACTION").contains(action);
        String system = "You help the team of the shop \"" + shop + "\" in their customer chat inbox. " + task
                + (forOwner ? " Write it for the shop owner in the language they will understand best: the language of the conversation."
                            : " Write ONLY the message text the shop will send: in the customer's language and dialect (Egyptian Arabic if they write Egyptian Arabic), short and natural, polite (حضرتك / يا فندم), no made-up prices, stock, discounts or delivery promises; when you do not know a fact, ask the customer instead of inventing it.")
                + " Output only the text, without quotes or labels.";
        String user = "CONVERSATION:\n" + convo + (d.isEmpty() ? "" : "\nSHOP DRAFT:\n" + d);
        try {
            var turn = ai.next(system, List.of(AiProvider.Turn.user(user)), List.of());
            aiUsage.recordCall(tenantId, "INBOX_ASSIST", turn);
            String text = turn.text() == null ? "" : turn.text().trim().replaceAll("^[\"«]|[\"»]$", "");
            if (text.isEmpty()) throw BusinessException.conflict("AI_NOT_AVAILABLE", "The assistant did not answer, try again");
            return Map.of("action", action, "text", text, "forOwner", forOwner, "channel", r.get("channel"));
        } catch (AiProvider.AiUnavailableException e) {
            throw BusinessException.conflict("AI_NOT_AVAILABLE", "The assistant is not available right now");
        }
    }

    /** A pause that ran out is cleared, so the chat shows as the assistant's again (a takeover stays until the owner resumes). */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT3M")
    public void clearEndedPauses() { jdbc.sql("UPDATE chat.conversations SET ai_paused_until = NULL WHERE ai_paused_until < now() AND NOT takeover").update(); }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private Map<String, Object> row(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT c.*, %s AS customer_id, lm.content AS last_message, lm.role AS last_role, lm.by_owner AS last_by_owner, lm.kind AS last_kind, lm.media_url AS last_media,
                       %s AS needs_reply, %s AS waiting, %s AS paused, %s AS unread, 0 AS unread_count,
                       (SELECT count(*) FROM commerce.orders o WHERE o.customer_id = (%s)) AS orders_count
                FROM chat.conversations c
                LEFT JOIN LATERAL (SELECT content, role, by_owner, kind, media_url FROM chat.messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE
                WHERE c.id = :c AND c.tenant_id = :t AND c.channel <> 'TEST'
                """.formatted(ChatCustomer.SQL, NEEDS, WAITING, PAUSED, UNREAD, ChatCustomer.SQL))
                .param("c", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Chat not found"));
    }

    private Map<String, Object> view(UUID tenantId, Map<String, Object> r, Map<String, Boolean> aiOnByChannel) {
        Map<String, Object> m = new LinkedHashMap<>();
        String channel = (String) r.get("channel"), ext = (String) r.get("external_id");
        m.put("id", r.get("id"));
        m.put("channel", channel);
        String digits = ext == null ? "" : ext.replaceAll("\\D", "");
        String phone = "WHATSAPP".equals(channel) && digits.length() >= 8 ? "+" + digits : r.get("contact_phone") != null ? (String) r.get("contact_phone") : null;
        String customerName = (String) r.get("customer_name");
        if (customerName == null && r.get("customer_id") != null)
            customerName = jdbc.sql("SELECT name FROM commerce.customers WHERE id = :c").param("c", r.get("customer_id")).query(String.class).optional().orElse(null);
        String display = (String) r.get("display_name");
        m.put("customerName", customerName != null && !customerName.isBlank() ? customerName : display);
        m.put("customerPhone", phone);
        m.put("customerId", r.get("customer_id"));
        m.put("ordersCount", r.get("orders_count") == null ? 0 : ((Number) r.get("orders_count")).longValue());
        String last = (String) r.get("last_message");
        m.put("lastMessage", last == null ? (r.get("last_media") != null ? "📷" : null) : clip(last, 160));
        m.put("lastFromCustomer", "user".equals(r.get("last_role")));
        m.put("lastByOwner", Boolean.TRUE.equals(r.get("last_by_owner")));
        m.put("lastKind", r.get("last_kind"));
        m.put("updatedAt", r.get("updated_at"));
        m.put("lastCustomerAt", r.get("last_customer_at"));
        m.put("lastReplyAt", r.get("last_reply_at"));
        m.put("createdAt", r.get("created_at"));
        m.put("status", r.get("status"));
        m.put("needsHuman", Boolean.TRUE.equals(r.get("needs_human")));
        m.put("needsHumanReason", r.get("needs_human_reason"));
        m.put("needsHumanAt", r.get("needs_human_at"));
        boolean takeover = Boolean.TRUE.equals(r.get("takeover"));
        boolean paused = Boolean.TRUE.equals(r.get("paused"));
        boolean channelOn = aiOnByChannel.computeIfAbsent(channel, ch -> chat.enabled(tenantId, ch));
        m.put("takeover", takeover);
        m.put("takeoverAt", r.get("takeover_at"));
        m.put("aiPaused", paused);
        Object until = r.get("ai_paused_until");
        m.put("aiPausedUntil", paused && !takeover ? until : null);
        m.put("aiActive", !paused && channelOn);
        m.put("aiChannelOn", channelOn);
        m.put("unhappy", Boolean.TRUE.equals(r.get("unhappy")));
        m.put("unhappyReason", r.get("unhappy_reason"));
        m.put("unhappySource", r.get("unhappy_source"));
        boolean unread = Boolean.TRUE.equals(r.get("unread"));
        m.put("unread", unread);
        m.put("unreadCount", r.get("unread_count") == null ? 0 : ((Number) r.get("unread_count")).longValue());
        List<String> statuses = new ArrayList<>();
        if (Boolean.TRUE.equals(r.get("needs_reply"))) statuses.add("NEEDS_REPLY");
        if (Boolean.TRUE.equals(r.get("waiting"))) statuses.add("WAITING");
        if (Boolean.TRUE.equals(r.get("unhappy"))) statuses.add("UNHAPPY");
        if (takeover) statuses.add("TAKEOVER");
        statuses.add(String.valueOf(r.get("status")));
        m.put("statuses", statuses);
        m.put("priority", Boolean.TRUE.equals(r.get("unhappy")) || Boolean.TRUE.equals(r.get("needs_human")) ? "HIGH" : Boolean.TRUE.equals(r.get("needs_reply")) ? "MEDIUM" : "LOW");
        m.put("lastOrder", r.get("last_order"));
        return m;
    }

    private String channelName(UUID tenantId, String channel) {
        if (!"WHATSAPP".equals(channel) && !"FACEBOOK".equals(channel) && !"INSTAGRAM".equals(channel)) return null;
        return jdbc.sql("SELECT name FROM chat.channels WHERE tenant_id = :t AND platform = :p AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", channel).query(String.class).optional().orElse(null);
    }

    /** {name} and {shop} in an owner message (from a saved reply) become the customer's first name and the shop name. */
    private String fill(UUID tenantId, Map<String, Object> r, String text) {
        if (!text.contains("{")) return text;
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        String name = r.get("customer_id") != null ? jdbc.sql("SELECT name FROM commerce.customers WHERE id = :c").param("c", r.get("customer_id")).query(String.class).optional().orElse(null) : null;
        if (name == null || name.isBlank()) name = (String) r.get("display_name");
        String first = ChatService.firstName(name);
        return text.replace("{shop}", shop).replace("{name}", first == null ? "" : first).replaceAll("\\s{2,}", " ").trim();
    }

    private String imageUrl(UUID tenantId, UUID fileId) {
        if (fileId == null) return null;
        if (jdbc.sql("SELECT count(*) FROM core.files WHERE id = :f AND tenant_id = :t AND category = 'PRODUCT_IMAGE'").param("f", fileId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "The picture was not found");
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        return urls.baseUrl(host) + "/api/bff/files/" + fileId + "/content?variant=medium";
    }

    private static List<String> clean(Collection<String> in, Set<String> allowed) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : in) for (String v : raw.split(",")) {
            String s = v.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty()) continue;
            if (!allowed.contains(s)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown filter value: " + v.trim());
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
}

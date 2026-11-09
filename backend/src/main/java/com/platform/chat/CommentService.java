package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Comments on the shop's Facebook and Instagram posts and ads, in one list with the platform tag; the team answers publicly or privately, and the shop can let the AI do it:
 * <ul>
 * <li>Public reply: OFF, a fixed STAMP text (free), or a short AI answer (0.1 EGP from the credit) that answers a question from the real catalog.</li>
 * <li>Private message: Meta "private replies" (once per comment, within 7 days): the AI opens a chat about the product of the post and answers the question; the customer's
 * answers then come in as normal Messenger / Instagram messages and the AI seller carries on the sale (0.1 EGP for the opening, then the usual conversation billing: 1 EGP per 10 AI replies).</li>
 * </ul>
 * Needs the comment permissions (pages_read_user_content, pages_manage_engagement, instagram_manage_comments) from Meta App Review; until then it works for the app's testers.
 * People who only reacted (likes) cannot be messaged: Meta does not allow it.
 */
@Service
public class CommentService {
    private static final Logger log = LoggerFactory.getLogger(CommentService.class);
    private static final Pattern QUESTION = Pattern.compile("[؟?]|(?<![\\p{L}])(?:بكام|كام|سعر|السعر|متاح|متوفر|موجود|فين|ازاي|إزاي|امتى|إمتى|مقاس|مقاسات|الوان|ألوان|لون|شحن|توصيل|عنوان|how much|price|available|size|sizes|color|colour|delivery|shipping|where)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final TokenCrypto crypto;
    private final ChatService chat;
    private final AiProvider ai;
    private final AiUsageService aiUsage;
    private final com.platform.billing.OutboundBilling billing;
    private final OutboundThread thread;

    public CommentService(JdbcClient jdbc, MetaGraph graph, TokenCrypto crypto, @Lazy ChatService chat, AiProvider ai, AiUsageService aiUsage, com.platform.billing.OutboundBilling billing, OutboundThread thread) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.crypto = crypto;
        this.chat = chat;
        this.ai = ai;
        this.aiUsage = aiUsage;
        this.billing = billing;
        this.thread = thread;
    }

    // ---- webhook ----------------------------------------------------------------------------------------------------------

    /** One {@code changes} item of a page / instagram webhook. Only new comments are kept; the page's own comments and duplicates are ignored. */
    public void onChange(String platform, String accountId, JsonNode change) {
        String field = change.path("field").asText("");
        JsonNode v = change.path("value");
        String commentId, text, authorId, authorName, postId, parent;
        boolean ad = false;
        if ("FACEBOOK".equals(platform)) {
            if (!"feed".equals(field) || !"comment".equals(v.path("item").asText()) || !"add".equals(v.path("verb").asText())) return;
            commentId = v.path("comment_id").asText("");
            text = v.path("message").asText("");
            authorId = v.path("from").path("id").asText("");
            authorName = v.path("from").path("name").asText(null);
            postId = v.path("post_id").asText(null);
            parent = v.path("parent_id").asText(null);
            if (parent != null && parent.equals(postId)) parent = null;
            ad = v.hasNonNull("ad_id") || v.path("post").path("promotion_status").asText("").equalsIgnoreCase("active");
        } else {
            if (!"comments".equals(field)) return;
            commentId = v.path("id").asText("");
            text = v.path("text").asText("");
            authorId = v.path("from").path("id").asText("");
            authorName = v.path("from").path("username").asText(null);
            postId = v.path("media").path("id").asText(null);
            parent = v.path("parent_id").asText(null);
            ad = "AD".equalsIgnoreCase(v.path("media").path("media_product_type").asText("")) || v.hasNonNull("ad_id");
        }
        if (commentId.isEmpty() || text.isBlank() || authorId.equals(accountId)) return;
        var ch = jdbc.sql("SELECT tenant_id, token_enc FROM chat.channels WHERE platform = :p AND external_id = :e AND status = 'ACTIVE'").param("p", platform).param("e", accountId).query().listOfRows().stream().findFirst();
        if (ch.isEmpty()) return;
        UUID tenantId = (UUID) ch.get().get("tenant_id");
        String token = crypto.decrypt((String) ch.get().get("token_enc"));
        String[] post = postInfo(platform, postId, token);
        boolean question = QUESTION.matcher(text).find();
        UUID id = jdbc.sql("""
                INSERT INTO chat.social_comments (tenant_id, platform, comment_id, parent_comment_id, post_id, post_permalink, post_text, post_image, is_ad, author_id, author_name, text, question)
                VALUES (:t, :p, :c, :pa, :po, :pl, :pt, :pi, :ad, :ai, :an, :x, :q) ON CONFLICT (comment_id) DO NOTHING RETURNING id
                """).param("t", tenantId).param("p", platform).param("c", commentId).param("pa", parent).param("po", postId).param("pl", post[0]).param("pt", clip(post[1], 2000))
                .param("pi", post[2]).param("ad", ad).param("ai", authorId).param("an", clip(authorName, 120)).param("x", clip(text, 2000)).param("q", question).query(UUID.class).optional().orElse(null);
        if (id == null) return;   // already handled
        try { automate(tenantId, id, token); } catch (RuntimeException e) { log.warn("Comment automation failed for {}: {}", commentId, e.getMessage()); }
    }

    /** {permalink, caption/message, picture} of the post the comment is on; nulls when Meta does not tell. */
    private String[] postInfo(String platform, String postId, String token) {
        if (postId == null || postId.isBlank()) return new String[3];
        try {
            var m = "FACEBOOK".equals(platform) ? graph.get(postId + "?fields=message,permalink_url,full_picture", token) : graph.get(postId + "?fields=caption,permalink,media_url,thumbnail_url", token);
            return new String[] {(String) m.getOrDefault("permalink_url", m.get("permalink")), (String) m.getOrDefault("message", m.get("caption")),
                    (String) m.getOrDefault("full_picture", m.getOrDefault("thumbnail_url", m.get("media_url")))};
        } catch (RuntimeException e) {
            return new String[3];
        }
    }

    private void automate(UUID tenantId, UUID id, String token) {
        var s = settings(tenantId);
        var c = row(tenantId, id);
        boolean question = Boolean.TRUE.equals(c.get("question"));
        String mode = (String) s.get("publicReply");
        if ("STAMP".equals(mode) && s.get("stampText") != null) publicReply(tenantId, c, token, (String) s.get("stampText"), "STAMP");
        else if ("AI".equals(mode) && chat.enabled(tenantId)) {
            String ref = "comment-" + id;
            if (!billing.charge(tenantId, ref, "AI comment reply")) noCredit(tenantId);
            else {
                String text = null;
                boolean sent = false;
                try {
                    text = write(tenantId, c, false, Boolean.TRUE.equals(s.get("dmEnabled")));
                    if (text != null) { publicReply(tenantId, c, token, text, "AI"); sent = true; }
                } finally {
                    if (!sent) billing.refund(tenantId, ref, "Comment reply not sent");
                }
            }
        }
        if (Boolean.TRUE.equals(s.get("dmEnabled")) && (question || !Boolean.TRUE.equals(s.get("dmOnlyQuestions"))) && chat.enabled(tenantId, (String) c.get("platform"))) {
            String ref = "comment-dm-" + id;
            if (!billing.charge(tenantId, ref, "AI private reply to a comment")) noCredit(tenantId);
            else {
                boolean sent = false;
                try {
                    String text = write(tenantId, c, true, false);
                    if (text != null) { privateReply(tenantId, c, token, text); sent = true; }
                } finally {
                    if (!sent) billing.refund(tenantId, ref, "Private message not sent");
                }
            }
        }
    }

    private void noCredit(UUID tenantId) {
        billing.notifyNoCredit(tenantId, "comment", "رصيدك غير كافٍ للرد على التعليقات", "لم نرد على تعليق عملائك لأن الرصيد غير كافٍ. اشحن الرصيد ليرجع الرد الآلي.");
    }

    /** What the AI writes: a short public answer under the comment, or the first private message to the commenter about the post's product. */
    private String write(UUID tenantId, Map<String, Object> c, boolean privately, boolean dmFollows) {
        String shop = chat.shopName(tenantId);
        String agent = (String) chat.getSettings(tenantId).get("agentName");
        String post = c.get("post_text") == null ? "" : String.valueOf(c.get("post_text"));
        String facts = chat.productFacts(tenantId, post + " " + c.get("text"));
        String name = DummyName.check((String) c.get("author_name")) ? null : ChatService.firstName((String) c.get("author_name"));
        String system = "You write for the shop \"" + shop + "\" on " + ("FACEBOOK".equals(c.get("platform")) ? "Facebook" : "Instagram") + ". "
                + (privately
                    ? "Write the FIRST PRIVATE MESSAGE to a person who commented on the shop's post" + (Boolean.TRUE.equals(c.get("is_ad")) ? " (an ad)" : "") + ". Greet them"
                      + (name != null ? " by name (" + name + ")" : "") + (agent != null && !agent.isBlank() ? ", introduce yourself as " + agent + " from " + shop : "")
                      + ", say you saw their comment, answer their question using ONLY the product facts below, and end with one easy question that moves them toward ordering (size, color, where to deliver). 2-4 short sentences."
                    : "Write a SHORT PUBLIC REPLY under a comment (1-2 sentences). If it is a question, answer it using ONLY the product facts below; never invent a price, stock or delivery promise; if the facts do not answer it, say we will send the details privately."
                      + (dmFollows ? " Mention briefly that we sent them a private message." : "") + " Never post phone numbers or links.")
                + " Same language and dialect as the comment (Egyptian Arabic if they write it), warm and respectful (حضرتك). Output only the message text.";
        String user = "POST: " + clip(post, 600) + "\nCOMMENT: " + c.get("text") + (facts.isBlank() ? "\nPRODUCT FACTS: none found; do not mention prices." : "\nPRODUCT FACTS:\n" + clip(facts, 2500));
        try {
            var turn = ai.next(system, List.of(AiProvider.Turn.user(user)), List.of());
            aiUsage.recordCall(tenantId, privately ? "COMMENT_DM" : "COMMENT_REPLY", turn);
            String t = turn.text() == null ? "" : turn.text().trim().replaceAll("^[\"«]|[\"»]$", "");
            return t.isBlank() ? null : clip(t, 1900);
        } catch (AiProvider.AiUnavailableException e) {
            return null;
        }
    }

    private void publicReply(UUID tenantId, Map<String, Object> c, String token, String text, String by) {
        String path = "FACEBOOK".equals(c.get("platform")) ? c.get("comment_id") + "/comments" : c.get("comment_id") + "/replies";
        try {
            graph.post(path, token, Map.of("message", text));
            jdbc.sql("UPDATE chat.social_comments SET status = 'REPLIED', reply_text = :x, replied_by = :b, replied_at = now(), error = NULL WHERE id = :i")
                    .param("x", text).param("b", by).param("i", c.get("id")).update();
        } catch (MetaGraph.MetaException e) {
            jdbc.sql("UPDATE chat.social_comments SET error = :e WHERE id = :i").param("e", clip("Public reply failed: " + e.getMessage(), 300)).param("i", c.get("id")).update();
            throw BusinessException.conflict("META_REJECTED", "Meta did not accept the reply: " + e.getMessage());
        }
    }

    private void privateReply(UUID tenantId, Map<String, Object> c, String token, String text) {
        if (!"NONE".equals(c.get("dm_status")) && !"FAILED".equals(c.get("dm_status"))) throw BusinessException.conflict("ALREADY_SENT", "A private message was already sent for this comment");
        if (c.get("created_at") instanceof java.sql.Timestamp ts && ts.toInstant().isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))) {
            jdbc.sql("UPDATE chat.social_comments SET dm_status = 'NOT_ALLOWED' WHERE id = :i").param("i", c.get("id")).update();
            throw BusinessException.conflict("TOO_LATE", "Meta allows a private message only within 7 days of the comment");
        }
        try {
            graph.post("me/messages", token, Map.of("recipient", Map.of("comment_id", String.valueOf(c.get("comment_id"))), "message", Map.of("text", text)));
            jdbc.sql("UPDATE chat.social_comments SET dm_status = 'SENT', dm_text = :x, dm_at = now(), error = NULL, status = CASE WHEN status = 'UNREPLIED' THEN 'REPLIED' ELSE status END, replied_by = coalesce(replied_by, 'AI'), replied_at = coalesce(replied_at, now()) WHERE id = :i")
                    .param("x", text).param("i", c.get("id")).update();
            thread.record(tenantId, (String) c.get("platform"), (String) c.get("author_id"), "COMMENT_DM", text);   // the chat shows it, and the commenter's answer is tagged as coming from a comment
        } catch (MetaGraph.MetaException e) {
            jdbc.sql("UPDATE chat.social_comments SET dm_status = 'FAILED', error = :e WHERE id = :i").param("e", clip("Private message failed: " + e.getMessage(), 300)).param("i", c.get("id")).update();
            throw BusinessException.conflict("META_REJECTED", "Meta did not accept the private message: " + e.getMessage());
        }
    }

    // ---- the team ---------------------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, String platform, String status, String post, String q, Integer cursor) {
        String pl = platform == null || platform.isBlank() ? null : platform.toUpperCase(java.util.Locale.ROOT);
        String st = status == null || status.isBlank() ? null : status.toUpperCase(java.util.Locale.ROOT);
        if (pl != null && !List.of("FACEBOOK", "INSTAGRAM").contains(pl)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown platform");
        if (st != null && !List.of("UNREPLIED", "REPLIED", "IGNORED").contains(st)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status");
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("%", "\\%") + "%";
        int off = Math.max(0, cursor == null ? 0 : cursor);
        String where = " WHERE tenant_id = :t AND (CAST(:p AS varchar) IS NULL OR platform = CAST(:p AS varchar)) AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))"
                + " AND (CAST(:po AS varchar) IS NULL OR post_id = CAST(:po AS varchar)) AND (CAST(:q AS varchar) IS NULL OR text ILIKE CAST(:q AS varchar) OR author_name ILIKE CAST(:q AS varchar) OR post_text ILIKE CAST(:q AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM chat.social_comments" + where).param("t", tenantId).param("p", pl).param("s", st).param("po", post).param("q", like).query(Long.class).single();
        var rows = Rows.camel(jdbc.sql("SELECT * FROM chat.social_comments" + where + " ORDER BY created_at DESC LIMIT 40 OFFSET :o")
                .param("t", tenantId).param("p", pl).param("s", st).param("po", post).param("q", like).param("o", off).query().listOfRows());
        rows.forEach(r -> r.remove("tenantId"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", rows);
        out.put("total", total);
        out.put("nextCursor", off + rows.size() < total ? off + rows.size() : null);
        return out;
    }

    public Map<String, Object> reply(UUID tenantId, UUID id, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 1900) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the reply (up to 1900 characters)");
        var c = row(tenantId, id);
        publicReply(tenantId, c, token(tenantId, (String) c.get("platform")), t, "OWNER");
        return Rows.camel(row(tenantId, id));
    }

    /** A private message to the commenter; empty text: the AI writes it (0.1 EGP). */
    public Map<String, Object> dm(UUID tenantId, UUID id, String text) {
        var c = row(tenantId, id);
        String t = text == null ? "" : text.trim();
        if (t.length() > 1900) throw BusinessException.badRequest("VALIDATION_ERROR", "The message is too long");
        if (t.isEmpty()) {
            String ref = "comment-dm-" + id;
            if (!billing.charge(tenantId, ref, "AI private reply to a comment")) throw BusinessException.conflict("NO_CREDIT", "Not enough credit for the AI");
            boolean sent = false;
            try {
                t = write(tenantId, c, true, false);
                if (t == null) throw BusinessException.conflict("AI_NOT_AVAILABLE", "The assistant is not available right now");
                privateReply(tenantId, c, token(tenantId, (String) c.get("platform")), t);
                sent = true;
            } finally {
                if (!sent) billing.refund(tenantId, ref, "Private message not sent");
            }
            return Rows.camel(row(tenantId, id));
        }
        privateReply(tenantId, c, token(tenantId, (String) c.get("platform")), t);
        return Rows.camel(row(tenantId, id));
    }

    /** An AI suggestion for the team to edit (public or private); nothing is sent. Free (like AI Assist in the inbox). */
    public Map<String, Object> suggest(UUID tenantId, UUID id, boolean privately) {
        var c = row(tenantId, id);
        String t = write(tenantId, c, privately, false);
        if (t == null) throw BusinessException.conflict("AI_NOT_AVAILABLE", "The assistant is not available right now");
        return Map.of("text", t);
    }

    public Map<String, Object> ignore(UUID tenantId, UUID id) {
        row(tenantId, id);
        jdbc.sql("UPDATE chat.social_comments SET status = 'IGNORED' WHERE id = :i").param("i", id).update();
        return Rows.camel(row(tenantId, id));
    }

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT public_reply, stamp_text, dm_enabled, dm_only_questions FROM chat.comment_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publicReply", r.map(x -> x.get("public_reply")).orElse("OFF"));
        m.put("stampText", r.map(x -> x.get("stamp_text")).orElse(null));
        m.put("dmEnabled", r.map(x -> x.get("dm_enabled")).orElse(false));
        m.put("dmOnlyQuestions", r.map(x -> x.get("dm_only_questions")).orElse(false));
        m.put("aiPriceMinor", billing.priceMinor());
        m.put("messagePriceMinor", billing.priceMinor());
        var today = jdbc.sql("""
                SELECT count(*) FILTER (WHERE kind = 'MESSAGE' AND ref LIKE 'comment-dm-%') - count(*) FILTER (WHERE kind = 'REFUND' AND ref LIKE 'comment-dm-%') AS dms,
                       count(*) FILTER (WHERE kind = 'MESSAGE' AND ref LIKE 'comment-%' AND ref NOT LIKE 'comment-dm-%') - count(*) FILTER (WHERE kind = 'REFUND' AND ref LIKE 'comment-%' AND ref NOT LIKE 'comment-dm-%') AS replies,
                       coalesce(-sum(amount_minor) FILTER (WHERE kind IN ('MESSAGE', 'REFUND') AND ref LIKE 'comment-%'), 0) AS cost
                FROM billing.wallet_entries WHERE tenant_id = :t AND created_at >= date_trunc('day', now())
                """).param("t", tenantId).query().singleRow();
        m.put("todayReplies", ((Number) today.get("replies")).longValue());
        m.put("todayDms", ((Number) today.get("dms")).longValue());
        m.put("todayCostMinor", ((Number) today.get("cost")).longValue());
        m.put("connected", jdbc.sql("SELECT array_to_string(array_agg(DISTINCT platform), ',') FROM chat.channels WHERE tenant_id = :t AND platform IN ('FACEBOOK','INSTAGRAM') AND status = 'ACTIVE'").param("t", tenantId).query(String.class).optional().orElse(""));
        return m;
    }

    public Map<String, Object> saveSettings(UUID tenantId, String publicReply, String stampText, Boolean dmEnabled, Boolean dmOnlyQuestions) {
        String mode = publicReply == null ? "OFF" : publicReply.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("OFF", "STAMP", "AI").contains(mode)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown reply mode");
        String stamp = stampText == null ? null : stampText.trim();
        if ("STAMP".equals(mode) && (stamp == null || stamp.isEmpty())) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the fixed reply text");
        if (stamp != null && stamp.length() > 300) throw BusinessException.badRequest("VALIDATION_ERROR", "The fixed reply is too long (up to 300 characters)");
        jdbc.sql("""
                INSERT INTO chat.comment_settings (tenant_id, public_reply, stamp_text, dm_enabled, dm_only_questions) VALUES (:t, :m, :s, :d, :q)
                ON CONFLICT (tenant_id) DO UPDATE SET public_reply = :m, stamp_text = :s, dm_enabled = :d, dm_only_questions = :q, updated_at = now()
                """).param("t", tenantId).param("m", mode).param("s", stamp == null || stamp.isEmpty() ? null : stamp).param("d", Boolean.TRUE.equals(dmEnabled)).param("q", Boolean.TRUE.equals(dmOnlyQuestions)).update();
        return settings(tenantId);
    }

    private String token(UUID tenantId, String platform) {
        String enc = jdbc.sql("SELECT token_enc FROM chat.channels WHERE tenant_id = :t AND platform = :p AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", platform).query(String.class).optional()
                .orElseThrow(() -> BusinessException.conflict("NOT_CONNECTED", "Connect the page first"));
        return crypto.decrypt(enc);
    }

    private Map<String, Object> row(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM chat.social_comments WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Comment not found"));
    }

    private static String clip(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }
}

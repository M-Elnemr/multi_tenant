package com.platform.chat;

import com.platform.billing.WalletService;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Questions the AI seller could not answer, as a conversation with the shop owner: the owner is told (in the app, as a phone notification and as a WhatsApp from the shop's own number),
 * opens the questions screen where each question sits next to its customer and chat, and answers once. The assistant then words the answer in its own dialect and sends it to the customer
 * in the same chat (WhatsApp, Instagram, Messenger) or leaves it in the website chat for when the customer comes back, so the customer is never left waiting for nothing.
 */
@Service
public class QuestionService {
    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private final JdbcClient jdbc;
    private final ChatService chat;
    private final EvolutionService evolution;
    private final MetaChannelService meta;
    private final WalletService wallet;
    private final ProductShare share;
    private final OpenUrls urls;
    private final ExecutorService pool;
    private final ChatDelivery delivery;

    public QuestionService(JdbcClient jdbc, ChatService chat, EvolutionService evolution, MetaChannelService meta, WalletService wallet, ProductShare share, OpenUrls urls, ChatDelivery delivery,
                           @Value("${app.evolution.async:true}") boolean async) {
        this.delivery = delivery;
        this.jdbc = jdbc;
        this.chat = chat;
        this.evolution = evolution;
        this.meta = meta;
        this.wallet = wallet;
        this.share = share;
        this.urls = urls;
        this.pool = async ? Executors.newFixedThreadPool(2) : null;
    }

    private static final String SELECT = """
            SELECT u.id, u.question, u.status, u.answer, u.answered_at, u.reply_text, u.delivery, u.delivery_note, u.created_at, u.conversation_id,
                   c.channel, c.external_id, c.client_id
            FROM chat.unanswered u LEFT JOIN chat.conversations c ON c.id = u.conversation_id
            """;

    // ---- telling the owner -------------------------------------------------------------------------------------------------------

    /** The assistant flagged a question: tell the owner everywhere we can. Never throws, so the customer's chat is not affected. */
    @EventListener
    public void onAsked(SellerTools.QuestionAsked e) {
        try {
            var q = one(e.tenantId(), e.id());
            String who = label(q);
            String question = String.valueOf(q.get("question"));
            wallet.notifyOwner(e.tenantId(), "سؤال جديد من عميل / New customer question", who + ": " + (question.length() > 160 ? question.substring(0, 160) + "…" : question), "AI_QUESTION");
            String self = evolution.selfNumber(e.tenantId());
            if (self != null) {
                String host = urls.hosts(List.of(e.tenantId())).get(e.tenantId());
                String link = host == null ? "" : "\n" + urls.baseUrl(host) + "/dashboard/questions";
                evolution.sendFree(e.tenantId(), self, "❓ سؤال من عميل (" + who + "):\n" + question + "\n\nافتح الشاشة وجاوبه وهنكمل الكلام معاه لوحدنا / Open the screen and answer, we continue the chat with the customer for you." + link);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not tell the owner about question {}: {}", e.id(), ex.getMessage());
        }
    }

    /** "Nada (+2010…) on WhatsApp": a short label for a notification. */
    private String label(Map<String, Object> q) {
        String name = (String) q.get("customerName"), phone = (String) q.get("customerPhone");
        String channel = q.get("channel") == null ? "" : switch (String.valueOf(q.get("channel"))) { case "WHATSAPP" -> "WhatsApp"; case "INSTAGRAM" -> "Instagram"; case "FACEBOOK" -> "Messenger"; default -> "الموقع / Website"; };
        String who = name != null ? name : phone != null ? phone : "عميل / Customer";
        return channel.isEmpty() ? who : who + " · " + channel;
    }

    // ---- the screen ------------------------------------------------------------------------------------------------------------

    /** Questions of this shop, open ones first, each with its customer. {@code status} is OPEN, ANSWERED, DISMISSED or null for all. */
    public List<Map<String, Object>> list(UUID tenantId, String status) {
        String st = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
        if (st != null && !List.of("OPEN", "ANSWERED", "DISMISSED").contains(st)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status");
        var rows = Rows.camel(jdbc.sql(SELECT + " WHERE u.tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR u.status = CAST(:s AS varchar)) ORDER BY (u.status = 'OPEN') DESC, u.created_at DESC LIMIT 100")
                .param("t", tenantId).param("s", st).query().listOfRows());
        Map<String, String> names = new LinkedHashMap<>();
        for (var r : rows) addCustomer(tenantId, r, names);
        return rows;
    }

    /** One question with the last messages of its chat, so the owner sees what the customer was talking about. */
    public Map<String, Object> get(UUID tenantId, long id) {
        var q = one(tenantId, id);
        if (q.get("conversationId") != null) {
            var msgs = new ArrayList<>(Rows.camel(jdbc.sql("SELECT role, content, created_at FROM chat.messages WHERE conversation_id = :c ORDER BY id DESC LIMIT 20").param("c", q.get("conversationId")).query().listOfRows()));
            java.util.Collections.reverse(msgs);
            q.put("messages", msgs);
        } else q.put("messages", List.of());
        return q;
    }

    public long openCount(UUID tenantId) { return jdbc.sql("SELECT count(*) FROM chat.unanswered WHERE tenant_id = :t AND status = 'OPEN'").param("t", tenantId).query(Long.class).single(); }

    private Map<String, Object> one(UUID tenantId, long id) {
        var rows = Rows.camel(jdbc.sql(SELECT + " WHERE u.tenant_id = :t AND u.id = :i").param("t", tenantId).param("i", id).query().listOfRows());
        if (rows.isEmpty()) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Question not found");
        addCustomer(tenantId, rows.get(0), new LinkedHashMap<>());
        return rows.get(0);
    }

    /** Adds {@code customerName} and {@code customerPhone} (the customer's shop record by phone or account, when they exist). */
    private void addCustomer(UUID tenantId, Map<String, Object> row, Map<String, String> cache) {
        String channel = (String) row.get("channel"), ext = (String) row.get("externalId");
        String phone = "WHATSAPP".equals(channel) && ext != null && ext.replaceAll("\\D", "").length() >= 8 ? "+" + ext.replaceAll("\\D", "") : null;
        row.put("customerPhone", phone);
        String key = row.get("clientId") != null ? "u" + row.get("clientId") : phone != null ? "p" + phone : null;
        String name = null;
        if (key != null) {
            if (cache.containsKey(key)) name = cache.get(key);
            else {
                try {
                    name = row.get("clientId") != null
                            ? jdbc.sql("SELECT name FROM commerce.customers WHERE tenant_id = :t AND user_id = :u LIMIT 1").param("t", tenantId).param("u", row.get("clientId")).query(String.class).optional().orElse(null)
                            : jdbc.sql("SELECT name FROM commerce.customers WHERE tenant_id = :t AND phone = :p LIMIT 1").param("t", tenantId).param("p", PhoneNormalizer.normalize(phone)).query(String.class).optional().orElse(null);
                } catch (RuntimeException e) { name = null; }
                cache.put(key, name);
            }
        }
        row.put("customerName", name == null || name.isBlank() ? null : name);
        row.remove("clientId");
        row.remove("externalId");
    }

    // ---- answering ---------------------------------------------------------------------------------------------------------------

    /** The owner's answer: saved at once, then the assistant words it and sends it to the customer in the background. */
    public Map<String, Object> answer(UUID tenantId, UUID actor, long id, String text) {
        String a = text == null ? "" : text.trim();
        if (a.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the answer");
        if (a.length() > 1500) throw BusinessException.badRequest("VALIDATION_ERROR", "The answer is too long (up to 1500 characters)");
        int n = jdbc.sql("UPDATE chat.unanswered SET status = 'ANSWERED', resolved = TRUE, answer = :a, answered_at = now(), answered_by = :u, delivery = NULL, delivery_note = NULL, reply_text = NULL WHERE id = :i AND tenant_id = :t AND status = 'OPEN'")
                .param("a", a).param("u", actor).param("i", id).param("t", tenantId).update();
        if (n == 0) {
            one(tenantId, id);   // not found -> 404
            throw BusinessException.conflict("ALREADY_ANSWERED", "This question was already answered or dismissed");
        }
        run(() -> deliver(tenantId, id));
        return get(tenantId, id);
    }

    /** Sends again the reply that could not be delivered (the same words, nothing is generated twice). */
    public Map<String, Object> retry(UUID tenantId, long id) {
        var q = one(tenantId, id);
        if (!"ANSWERED".equals(q.get("status")) || !"FAILED".equals(q.get("delivery"))) throw BusinessException.conflict("NOTHING_TO_RETRY", "Only an answer that could not be delivered can be sent again");
        jdbc.sql("UPDATE chat.unanswered SET delivery = NULL, delivery_note = NULL WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        run(() -> deliver(tenantId, id));
        return get(tenantId, id);
    }

    /** The owner decided there is nothing to answer. */
    public void dismiss(UUID tenantId, long id) {
        if (jdbc.sql("UPDATE chat.unanswered SET status = 'DISMISSED', resolved = TRUE WHERE id = :i AND tenant_id = :t AND status = 'OPEN'").param("i", id).param("t", tenantId).update() == 0) {
            one(tenantId, id);
            throw BusinessException.conflict("ALREADY_ANSWERED", "This question was already answered or dismissed");
        }
    }

    private void run(Runnable job) {
        Runnable safe = () -> { try { job.run(); } catch (RuntimeException e) { log.warn("Delivering an owner's answer failed", e); } };
        if (pool != null) pool.execute(safe); else safe.run();
    }

    private static boolean arabic(String s) { return s != null && s.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF); }

    /** Words the answer (the assistant, or the plain answer when the model is down) and sends it to the customer; records how it went. */
    void deliver(UUID tenantId, long id) {
        var row = jdbc.sql(SELECT + " WHERE u.tenant_id = :t AND u.id = :i").param("t", tenantId).param("i", id).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null || row.get("conversation_id") == null) return;
        UUID conv = (UUID) row.get("conversation_id");
        String question = (String) row.get("question"), answer = (String) row.get("answer"), channel = (String) row.get("channel"), ext = (String) row.get("external_id");
        String text = (String) row.get("reply_text");
        List<Map<String, Object>> products = List.of();
        if (text == null) {
            ChatService.Reply reply = null;
            try { reply = chat.replyToOwnerAnswer(tenantId, conv, question, answer); } catch (RuntimeException e) { log.warn("The assistant could not word the owner's answer: {}", e.getMessage()); }
            if (reply != null && reply.text() != null && !reply.text().isBlank()) { text = reply.text(); products = reply.products(); }
            else {
                text = arabic(question) ? "بخصوص سؤالك «" + question + "»:\n" + answer : "About your question «" + question + "»:\n" + answer;
                jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', :x)").param("c", conv).param("x", text).update();
                jdbc.sql("UPDATE chat.conversations SET updated_at = now() WHERE id = :c").param("c", conv).update();
            }
            jdbc.sql("UPDATE chat.unanswered SET reply_text = :r WHERE id = :i").param("r", text.length() > 4000 ? text.substring(0, 4000) : text).param("i", id).update();
        }
        var sent = delivery.send(tenantId, channel, ext, text, products);
        jdbc.sql("UPDATE chat.unanswered SET delivery = :d, delivery_note = :n WHERE id = :i").param("d", sent.delivery()).param("n", sent.note()).param("i", id).update();
    }
}

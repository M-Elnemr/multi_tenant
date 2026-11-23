package com.platform.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DR-R: a customer message that no AI model could answer is never answered with a «we are busy» text. The turn is kept in {@code chat.deferred_replies} and retried in the background at +15 s, +45 s,
 * +2 min and +5 min after it was deferred, through the normal reply path (health breaker and fallback model active, the customer's message and the points of that turn are NOT taken again), and the
 * answer is delivered like any assistant reply: the website chat shows it from the saved message (the widget polls the history), the messaging channels get it through {@link ReplyDelivery}.
 * Only when the last retry fails does the customer get ONE short holding message and the chat is flagged for the shop team (the owner is notified as for any needs-human chat).
 * Off with {@code AI_DEFERRED=false}: a failed turn is then answered the old way.
 */
@Service
public class DeferredReplies {
    private static final Logger log = LoggerFactory.getLogger(DeferredReplies.class);

    /** Seconds after the turn was deferred at which retry 1, 2, 3 and 4 run. */
    static final long[] RETRY_AT_SECONDS = {15, 45, 120, 300};

    /** What carries a deferred answer (or the holding message) to a customer outside the live reply: the existing outbound path of each channel, see {@link ChatDelivery}. */
    public interface ReplyDelivery {
        ChatDelivery.Result send(UUID tenantId, String channel, String externalId, String text, List<Map<String, Object>> products);
    }

    /** One retry in progress: what ChatService needs to answer it without taking the customer's message or points a second time. */
    static final class Run {
        final UUID tenantId, clientId;
        final String channel, externalId, text, displayName;
        final long userMessageId;
        volatile boolean failedAgain;
        Run(UUID tenantId, String channel, String externalId, UUID clientId, String text, String displayName, long userMessageId) {
            this.tenantId = tenantId; this.channel = channel; this.externalId = externalId; this.clientId = clientId; this.text = text; this.displayName = displayName; this.userMessageId = userMessageId;
        }
    }

    private final JdbcClient jdbc;
    private final AiEvents events;
    private final ObjectProvider<ChatService> chat;
    private final ObjectProvider<ChatDelivery> chatDelivery;
    private final ObjectProvider<com.platform.commerce.GenderService> genders;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "deferred-reply"); t.setDaemon(true); return t; });
    private volatile ReplyDelivery delivery;
    @Value("${app.ai.deferred.enabled:true}")
    private volatile boolean enabled = true;
    @Value("${app.ai.deferred.scheduler:true}")
    private boolean scheduler = true;

    public DeferredReplies(JdbcClient jdbc, AiEvents events, ObjectProvider<ChatService> chat, ObjectProvider<ChatDelivery> chatDelivery, ObjectProvider<com.platform.commerce.GenderService> genders) {
        this.jdbc = jdbc;
        this.events = events;
        this.chat = chat;
        this.chatDelivery = chatDelivery;
        this.genders = genders;
        this.delivery = (t, c, e, text, products) -> this.chatDelivery.getObject().send(t, c, e, text, products);
    }

    public boolean enabled() { return enabled; }

    public void enabled(boolean on) { this.enabled = on; }

    /** Replaces how answers reach messaging channels (tests). */
    public void delivery(ReplyDelivery d) { this.delivery = d; }

    public ReplyDelivery delivery() { return delivery; }

    /** Keeps an unanswered turn for retry. The first retry runs 15 seconds from now. */
    public void defer(UUID tenantId, UUID conversationId, String channel, String externalId, UUID clientId, long userMessageId, String text, String displayName, String why) {
        jdbc.sql("INSERT INTO chat.deferred_replies (tenant_id, conversation_id, channel, external_id, client_id, user_message_id, message_text, display_name, next_attempt_at, last_error) "
                        + "VALUES (:t, :c, :ch, :e, :cl, :u, :x, :n, now() + make_interval(secs => :s), :w)")
                .param("t", tenantId).param("c", conversationId).param("ch", channel).param("e", externalId).param("cl", clientId).param("u", userMessageId).param("x", text)
                .param("n", displayName == null ? null : displayName.length() > 120 ? displayName.substring(0, 120) : displayName).param("s", (double) RETRY_AT_SECONDS[0])
                .param("w", why == null ? null : why.length() > 300 ? why.substring(0, 300) : why).update();
    }

    // ---- the background loop ---------------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.ai.deferred.poll-ms:5000}", initialDelay = 20_000)
    void tick() {
        if (!enabled || !scheduler) return;
        try { runDue(); } catch (RuntimeException e) { log.warn("Deferred replies tick failed: {}", e.toString()); }
    }

    /**
     * Claims every turn that is due (a claim moves its next attempt 3 minutes ahead, so no other instance and no later tick takes it while it runs) and starts its attempt on the pool.
     * Returns the claimed ids; {@link #attempt} does the work (a test calls it directly).
     */
    public List<Map<String, Object>> claimDue() {
        return jdbc.sql("UPDATE chat.deferred_replies SET next_attempt_at = now() + interval '3 minutes', updated_at = now() WHERE id IN "
                        + "(SELECT id FROM chat.deferred_replies WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 8 FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, tenant_id, conversation_id, channel, external_id, client_id, user_message_id, message_text, display_name, attempts, created_at").query().listOfRows();
    }

    void runDue() { for (var row : claimDue()) pool.submit(() -> { try { attempt(row); } catch (RuntimeException e) { log.warn("Deferred reply attempt failed: {}", e.toString()); } }); }

    /** One retry of a claimed turn. */
    public void attempt(Map<String, Object> row) {
        UUID id = (UUID) row.get("id"), tenantId = (UUID) row.get("tenant_id"), conv = (UUID) row.get("conversation_id"), clientId = (UUID) row.get("client_id");
        String channel = (String) row.get("channel"), externalId = (String) row.get("external_id"), text = (String) row.get("message_text"), name = (String) row.get("display_name");
        long userMsg = ((Number) row.get("user_message_id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        ChatService chat = this.chat.getObject();
        // somebody (the AI on a later message, a person from the inbox) already answered after this message: nothing is left to answer
        boolean answered = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.messages WHERE conversation_id = :c AND role = 'assistant' AND id > :u)").param("c", conv).param("u", userMsg).query(Boolean.class).single();
        if (answered) { finish(id, "DONE", "SUPERSEDED"); return; }
        if (chat.humanHandles(tenantId, channel, externalId)) { finish(id, "DONE", "HUMAN"); return; }
        long t0 = System.nanoTime();
        Run run = new Run(tenantId, channel, externalId, clientId, text, name, userMsg);
        ChatService.Reply reply = null;
        String error = null;
        try { reply = chat.replyDeferred(run); }
        catch (RuntimeException e) { run.failedAgain = true; error = e.toString(); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!run.failedAgain) {
            if (reply != null && reply.text() != null && !reply.text().isBlank()) deliver(tenantId, conv, channel, externalId, reply.text(), reply.products());
            finish(id, "DONE", "RECOVERED");
            events.record(tenantId, conv, channel, null, "DEFERRED_RECOVERED", "attempt " + (attempts + 1) + (reply != null && reply.text() != null && !reply.text().isBlank() ? "" : ", nothing to send"), ms);
            return;
        }
        int next = attempts + 1;
        if (next < RETRY_AT_SECONDS.length) {
            jdbc.sql("UPDATE chat.deferred_replies SET attempts = :a, next_attempt_at = created_at + make_interval(secs => :s), last_error = :e, updated_at = now() WHERE id = :id")
                    .param("a", (short) next).param("s", (double) RETRY_AT_SECONDS[next]).param("e", error == null ? "every model failed" : error.length() > 300 ? error.substring(0, 300) : error).param("id", id).update();
            events.record(tenantId, conv, channel, null, "DEFERRED_RETRY", "attempt " + next + " failed, next in " + Math.max(0, RETRY_AT_SECONDS[next] - RETRY_AT_SECONDS[next - 1]) + " s", ms);
            return;
        }
        giveUp(id, tenantId, conv, channel, externalId, text, ms);
    }

    /** Every retry failed: ONE holding message per conversation, the chat is flagged for the team, the owner is told. */
    private void giveUp(UUID id, UUID tenantId, UUID conv, String channel, String externalId, String customerText, long ms) {
        // the claim is atomic: only the row that wins it sends the holding message (a second deferred turn of the same chat within 12 hours sends none)
        int won = jdbc.sql("UPDATE chat.deferred_replies SET status = 'HELD', attempts = :a, holding_sent = TRUE, note = 'HOLDING', updated_at = now() WHERE id = :id AND status = 'PENDING' "
                        + "AND NOT EXISTS (SELECT 1 FROM chat.deferred_replies d WHERE d.conversation_id = :c AND d.holding_sent AND d.id <> :id AND d.created_at > now() - interval '12 hours')")
                .param("a", (short) RETRY_AT_SECONDS.length).param("id", id).param("c", conv).update();
        ChatService chat = this.chat.getObject();
        if (won == 1) {
            String holding = chat.holdForTeam(tenantId, conv, channel, customerText);
            deliver(tenantId, conv, channel, externalId, holding, List.of());
        } else {
            jdbc.sql("UPDATE chat.deferred_replies SET status = 'HELD', attempts = :a, note = 'ALREADY_HELD', updated_at = now() WHERE id = :id AND status = 'PENDING'").param("a", (short) RETRY_AT_SECONDS.length).param("id", id).update();
            chat.flagTeam(tenantId, conv, channel, customerText);
        }
        events.record(tenantId, conv, channel, null, "DEFERRED_GAVE_UP", won == 1 ? "holding message sent, team flagged" : "team flagged (holding message already sent)", ms);
    }

    private void finish(UUID id, String status, String note) {
        jdbc.sql("UPDATE chat.deferred_replies SET status = :s, note = :n, updated_at = now() WHERE id = :id").param("s", status).param("n", note).param("id", id).update();
    }

    /**
     * The website chat needs nothing (the answer is a saved message the widget polls for). Messaging channels get it through the channel's own outbound path, once (a failed send is logged and
     * recorded for the shop, never retried, so the customer cannot receive it twice).
     */
    private void deliver(UUID tenantId, UUID conv, String channel, String externalId, String text, List<Map<String, Object>> products) {
        if (text == null || text.isBlank() || "WEB".equals(channel) || "TEST".equals(channel)) return;
        try {
            com.platform.shared.Gender g = "WHATSAPP".equals(channel) ? genders.getObject().forPhone(tenantId, externalId) : com.platform.shared.Gender.MALE;
            List<String> messages = ChatService.messages(g.apply(text));
            for (int i = 0; i < messages.size(); i++) {
                var res = delivery.send(tenantId, channel, externalId, messages.get(i), i == messages.size() - 1 ? products : List.of());
                if (res != null && "FAILED".equals(res.delivery())) {
                    log.warn("Deferred {} answer could not be sent: {}", channel, res.note());
                    events.record(tenantId, conv, channel, null, "DEFERRED_SEND_FAILED", res.note(), null);
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Deferred {} answer could not be sent: {}", channel, e.toString());
            events.record(tenantId, conv, channel, null, "DEFERRED_SEND_FAILED", e.toString(), null);
        }
    }
}

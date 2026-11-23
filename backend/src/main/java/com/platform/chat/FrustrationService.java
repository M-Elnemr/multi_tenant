package com.platform.chat;

import com.platform.audit.AuditService;
import com.platform.commerce.StoreContext;
import com.platform.shared.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What happens when a customer is genuinely upset ({@link FrustrationDetector}), by the shop's choice:
 * AI (the default): the assistant keeps answering but calms the customer first and solves the problem, never claiming an action it did not take;
 * MESSAGE: one fixed message the shop wrote is sent, the chat is flagged, the shop is told, and the assistant stays out of it;
 * SILENT: nothing is sent, the chat is flagged, the shop is told, and the assistant stays out of it.
 * The hand-over is made in the server before any model call, once per chat (a retried or duplicated delivery cannot repeat the message or the notification), and only the shop can give the chat back.
 */
@Service
@RestController
@RequestMapping("/api/v1/store/ai")
public class FrustrationService {
    private static final Logger log = LoggerFactory.getLogger(FrustrationService.class);
    static final String DEFAULT_AR = "بنعتذر لحضرتك عن أي تجربة غير مرضية. تم تحويل المحادثة لفريق خدمة العملاء لمراجعة المشكلة ومساعدتك في أقرب وقت.";
    static final String DEFAULT_EN = "We are sorry for any unsatisfactory experience. Your conversation has been passed to our customer service team, who will review the problem and help you as soon as possible.";

    private final JdbcClient jdbc;
    private final ChatTimeline timeline;
    private final ApplicationEventPublisher publisher;
    private final AuditService audit;

    public FrustrationService(JdbcClient jdbc, ChatTimeline timeline, ApplicationEventPublisher publisher, AuditService audit) {
        this.jdbc = jdbc;
        this.timeline = timeline;
        this.publisher = publisher;
        this.audit = audit;
    }

    public record Setting(String mode, String message) {}

    public Setting setting(UUID tenantId) {
        var r = jdbc.sql("SELECT frustration_mode, frustration_message FROM chat.settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        return new Setting(r.map(x -> (String) x.get("frustration_mode")).orElse("AI"), r.map(x -> (String) x.get("frustration_message")).orElse(null));
    }

    // ---- the shop's setting ---------------------------------------------------------------------------------------

    public record SettingReq(String mode, String message) {}

    @GetMapping("/frustration")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> get() {
        Setting s = setting(StoreContext.tenantId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode());
        m.put("message", s.message());
        m.put("defaultMessage", DEFAULT_AR);
        return m;
    }

    @PutMapping("/frustration")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> put(@RequestBody SettingReq r, Authentication a) {
        UUID t = StoreContext.tenantId();
        String mode = r.mode() == null ? "" : r.mode().trim().toUpperCase();
        if (!List.of("AI", "MESSAGE", "SILENT").contains(mode)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown mode").field("mode");
        String message = r.message() == null || r.message().isBlank() ? null : r.message().trim();
        if (message != null && message.length() > 600) throw BusinessException.badRequest("VALIDATION_ERROR", "The message is too long (up to 600 characters)").field("message");
        jdbc.sql("""
                INSERT INTO chat.settings (tenant_id, frustration_mode, frustration_message) VALUES (:t, :m, :x)
                ON CONFLICT (tenant_id) DO UPDATE SET frustration_mode = :m, frustration_message = :x, updated_at = now()
                """).param("t", t).param("m", mode).param("x", message).update();
        audit.record((UUID) a.getPrincipal(), t, "AI_FRUSTRATION_MODE_CHANGED", "ai", null, "{\"mode\":\"" + mode + "\"}");
        return get();
    }

    // ---- in a chat ------------------------------------------------------------------------------------------------

    /** The customer's messages in this chat, oldest first (the last six). */
    private List<String> recent(UUID conv) {
        return jdbc.sql("SELECT content FROM (SELECT id, content FROM chat.messages WHERE conversation_id = :c AND role = 'user' ORDER BY id DESC LIMIT 6) x ORDER BY id").param("c", conv).query(String.class).list();
    }

    /** What to send, or empty text for nothing. */
    public record Handover(String text) {}

    /**
     * Called for each customer message before the assistant is asked. Null: nothing to do (the assistant answers). Otherwise the chat has been handed over (or already was): send {@code text} when it is not empty.
     * The hand-over is one atomic update, so only the first of any number of simultaneous or repeated deliveries sends the message and notifies the shop.
     */
    public Handover handle(UUID tenantId, UUID conv, String channel, boolean english) {
        Setting s = setting(tenantId);
        if ("AI".equals(s.mode())) return null;
        var verdict = FrustrationDetector.judge(recent(conv));
        if (!verdict.genuine()) return null;
        String reason = verdict.reason().length() > 290 ? verdict.reason().substring(0, 290) : verdict.reason();
        int changed = jdbc.sql("""
                UPDATE chat.conversations SET frustration_paused = TRUE, frustration_at = now(), frustration_reason = :r,
                  unhappy = TRUE, unhappy_reason = coalesce(:r, unhappy_reason), unhappy_source = CASE WHEN unhappy_source = 'OWNER' THEN unhappy_source ELSE 'RULES' END, unhappy_at = now(),
                  needs_human = TRUE, needs_human_reason = :r, needs_human_at = now()
                WHERE id = :c AND tenant_id = :t AND NOT frustration_paused
                """).param("r", reason).param("c", conv).param("t", tenantId).update();
        if (changed == 0) return new Handover("");   // already handed over: the customer's later messages get no automatic reply at all
        jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'FRUSTRATION' WHERE conversation_id = :c AND status = 'SCHEDULED'").param("c", conv).update();
        timeline.event(tenantId, conv, "FRUSTRATION_HANDOVER", null, "{\"mode\":\"" + s.mode() + "\",\"wantsHuman\":" + verdict.wantsHuman() + "}");
        String last = recent(conv).stream().reduce((a, b) -> b).orElse("");
        publisher.publishEvent(new ChatNeedsHuman(tenantId, conv, channel, reason, last));   // the shop is told once, in the app and on the phone
        if ("SILENT".equals(s.mode())) return new Handover("");
        String text = s.message() != null ? s.message() : english ? DEFAULT_EN : DEFAULT_AR;
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', :x)").param("c", conv).param("x", text).update();
        log.info("Frustration handover in chat {} ({}): {}", conv, s.mode(), reason);
        return new Handover(text);
    }

    private static final String TONE = """

            THE CUSTOMER IS UPSET (strict, for this reply; it comes with the RULES):
            - Open with ONE short, sincere sentence that acknowledges their problem and apologises once if the shop may be at fault or it is unclear («حق حضرتك علينا», «أنا متفهم إن ده بيضايقك», "I am sorry about this, I understand why you are upset"). Calm and respectful: never argue, never blame the customer, never defend the shop, no jokes, at most one calm emoji.
            - No sales pitch, no upsell, no unrelated suggestions. Work on the actual problem: when it is about an order or a product use the tools (order_status, search_products, get_product) and give the real status and the practical next step.
            - NEVER say you contacted the shop, escalated, opened a ticket or that someone will call them unless you really called flag_unanswered in this very turn. If you cannot solve it, call flag_unanswered with their complaint, and only then tell them the shop team will look at it.
            - If they ask for a person, say you are passing it to the team and call flag_unanswered.
            """;

    /** The extra instruction for the assistant's reply when the shop chose AI and the customer is genuinely upset; empty otherwise. */
    public String toneBlock(UUID tenantId, UUID conv) {
        if (!"AI".equals(setting(tenantId).mode())) return "";
        return FrustrationDetector.judge(recent(conv)).genuine() ? TONE : "";
    }
}

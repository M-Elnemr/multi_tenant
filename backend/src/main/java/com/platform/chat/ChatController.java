package com.platform.chat;

import com.platform.commerce.StoreContext;
import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import com.platform.shared.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;

/** Storefront chat (customers, guests included), the owner's test chat and teaching, and the platform admin's global lessons. */
@RestController
public class ChatController {
    private static final Pattern CONV_ID = Pattern.compile("^[0-9a-f-]{36}$");
    private final ChatService chat;
    private final LoginThrottle throttle;
    private final AiEvalService eval;

    private final AiReadinessService readiness;
    private final MediaReader media;
    private final WebBurst webBurst;
    private final DeferredReplies deferred;

    public ChatController(ChatService chat, LoginThrottle throttle, AiEvalService eval, AiReadinessService readiness, MediaReader media, WebBurst webBurst, DeferredReplies deferred) {
        this.webBurst = webBurst;
        this.deferred = deferred;
        this.media = media;
        this.readiness = readiness;
        this.chat = chat;
        this.throttle = throttle;
        this.eval = eval;
    }

    /** {@code replyToMessageId}: the message of this chat the customer is answering (optional; checked against this chat and shop before it is used). */
    public record SendRequest(String conversationId, String message, Long replyToMessageId) {
        public SendRequest(String conversationId, String message) { this(conversationId, message, null); }
    }
    /** {@code replyToMessageId}: a message of the owner's own test chat to answer (so reply-aware behaviour can be tried and evaluated). */
    public record TestRequest(String message, Long replyToMessageId) {
        public TestRequest(String message) { this(message, null); }
    }
    public record SettingsRequest(Boolean enabled, String tone, Integer maxDiscountPct, String greeting, List<String> channels, String agentName, String agentAvatar) {}
    public record LessonRequest(String instruction, String exampleQuestion, String exampleAnswer) {}

    private static UUID clientOrNull(Authentication a) {
        boolean client = a != null && a.getPrincipal() instanceof UUID && a.getAuthorities().stream().anyMatch(x -> "ROLE_CUSTOMER".equals(x.getAuthority()));
        return client ? (UUID) a.getPrincipal() : null;
    }

    private static Map<String, Object> view(ChatService.Reply r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversationId", r.conversationId());
        m.put("reply", r.text());
        m.put("order", r.order());
        m.put("products", r.products());
        m.put("buttons", ReplyButtons.asMaps(ReplyButtons.pick(r.text())));
        if (r.messageId() > 0) m.put("messageId", r.messageId());
        if (r.userMessageId() > 0) m.put("userMessageId", r.userMessageId());
        return m;
    }

    // ---- storefront ------------------------------------------------------------------------------------------

    /** Public: tells the storefront whether to show the live-chat button. */
    @GetMapping("/api/v1/shop/chat/config")
    public Map<String, Object> config() {
        UUID t = StoreContext.tenantId();
        var s = chat.getSettings(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", chat.enabled(t, "WEB") && chat.hasPoints(t));
        m.put("greeting", s.get("greeting") == null ? "" : s.get("greeting"));
        m.put("agentName", s.get("agentName") == null ? "" : s.get("agentName"));
        m.put("agentAvatar", s.get("agentAvatar") == null ? "" : s.get("agentAvatar"));
        m.put("media", media.enabled());   // whether photos and voice notes can be read
        return m;
    }

    @GetMapping("/api/v1/shop/chat/history")
    public Object history(@RequestParam String conversationId, @RequestParam(required = false) Long around, @RequestParam(required = false) Boolean state, jakarta.servlet.http.HttpServletResponse res) {
        UUID tenant = StoreContext.tenantId();
        boolean pending = CONV_ID.matcher(conversationId).matches() && (webBurst.pending(tenant, conversationId) || deferred.hasOpen(tenant, UUID.fromString(conversationId)));   // DR-B: the assistant has not answered the latest lines yet (DR-R: or a retry of an unanswered turn is still running)
        res.setHeader("X-Chat-Pending", pending ? "1" : "0");
        List<Map<String, Object>> h = historyRows(tenant, conversationId, around);
        if (webBurst.enabled()) {   // the cards (order, products) of replies written after the line was saved: the page gets them with the reply
            h = new java.util.ArrayList<>(h);
            for (int i = 0; i < h.size(); i++) {
                if (!"assistant".equals(h.get(i).get("role")) || !(h.get(i).get("id") instanceof Number n)) continue;
                var x = webBurst.extras(n.longValue());
                if (x == null) continue;
                var row = new LinkedHashMap<>(h.get(i));
                row.putAll(x);
                h.set(i, row);
            }
        }
        if (Boolean.TRUE.equals(state)) { Map<String, Object> m = new LinkedHashMap<>(); m.put("messages", h); m.put("pending", pending); return m; }   // the widget's form; the plain list stays for older callers
        return h;
    }

    /** The customer is typing (the widget's heartbeat, about every 2 seconds): the assistant's wait for the end of the burst is extended. Cheap, in memory, never fails the page. */
    @PostMapping("/api/v1/shop/chat/typing")
    public Map<String, Object> typing(@RequestBody Map<String, String> r) {
        UUID t = StoreContext.openTenantId();
        String c = r == null ? null : r.get("conversationId");
        if (c != null && CONV_ID.matcher(c).matches()) webBurst.typing(t, c);
        return Map.of("ok", true);
    }

    private List<Map<String, Object>> historyRows(UUID tenant, String conversationId, Long around) {
        if (!CONV_ID.matcher(conversationId).matches()) return List.of();
        if (around != null) return chat.historyAround(tenant, "WEB", conversationId, around, 15, 25);   // the page around an old message the customer is jumping to (a quote)
        var h = new java.util.ArrayList<>(chat.history(tenant, "WEB", conversationId));
        if (!h.isEmpty() && "assistant".equals(h.get(h.size() - 1).get("role")) && h.get(h.size() - 1).get("content") instanceof String c) {   // the buttons of the question still waiting for its answer
            var last = new java.util.LinkedHashMap<>(h.get(h.size() - 1));
            last.put("buttons", ReplyButtons.asMaps(ReplyButtons.pick(c)));
            h.set(h.size() - 1, last);
        }
        return h;
    }

    /**
     * Public chat limits, three modes ({@code app.chat.rate-limit.mode}; the older {@code app.chat.rate-limit.enabled=true} still means STRICT). OFF is the owner's decision of 2026-11-18 and the default.
     * ABUSE is the recommended production setting: ceilings no real customer reaches (a person types well under 100 messages in 10 minutes) but that stop a script from spending the shop's points:
     * per chat 100 / 10 min, per address 1,000 / 10 min (many mobile customers share one carrier address), per shop 4,000 / hour on the public chat, and the same text 6 times in 2 minutes in one chat.
     * STRICT is the old 30 / 300 / 300-a-day. A refused message is a 429 before any model call, so nothing is charged. The general 600 requests per minute per address always applies.
     */
    @org.springframework.beans.factory.annotation.Value("${app.chat.rate-limit.enabled:false}")
    private boolean rateLimitEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.chat.rate-limit.mode:off}")
    private String rateLimitMode;

    private String limitMode() { return rateLimitEnabled ? "strict" : rateLimitMode == null ? "off" : rateLimitMode.trim().toLowerCase(java.util.Locale.ROOT); }

    private void messageLimits(UUID tenantId, String conversationId, HttpServletRequest req, String text) {
        String mode = limitMode();
        if (mode.equals("strict")) {
            throttle.check("chat-conv10|" + conversationId, 30, Duration.ofMinutes(10));
            throttle.check("chat-ip|" + ClientInfo.ip(req), 300, Duration.ofMinutes(10));
            throttle.check("chat-conv|" + conversationId, 300, Duration.ofDays(1));
        } else if (mode.equals("abuse")) {
            throttle.check("chat-conv10|" + conversationId, 100, Duration.ofMinutes(10));
            throttle.check("chat-ip|" + ClientInfo.ip(req), 1000, Duration.ofMinutes(10));
            throttle.check("chat-tenant|" + tenantId, 4000, Duration.ofHours(1));
            if (text != null && !text.isBlank()) throttle.check("chat-dup|" + conversationId + "|" + Integer.toHexString(text.trim().toLowerCase(java.util.Locale.ROOT).hashCode()), 6, Duration.ofMinutes(2));
        }
    }

    private void mediaLimit(String conversationId) {
        if (!limitMode().equals("off")) throttle.check("chat-media|" + conversationId, 20, Duration.ofMinutes(10));   // each read is a model call
    }

    @PostMapping("/api/v1/shop/chat")
    public Map<String, Object> send(@RequestBody SendRequest r, Authentication a, HttpServletRequest req) {
        UUID t = StoreContext.openTenantId();
        if (r.conversationId() == null || !CONV_ID.matcher(r.conversationId()).matches()) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid conversation");
        if (!chat.enabled(t, "WEB")) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_DISABLED", "Live chat is not available right now");
        // per chat: nobody types more than 30 messages in 10 minutes; per address a much higher ceiling, because many mobile customers share one carrier address
        messageLimits(t, r.conversationId(), req, r.message());
        var in = r.replyToMessageId() == null ? ChatService.ReplyInput.NONE : new ChatService.ReplyInput(List.of(ReplyRef.ofId(r.replyToMessageId())), List.of());
        if (webBurst.enabled() && r.message() != null && !r.message().isBlank()) {   // DR-B: the line is saved now, the assistant answers the burst once
            var stored = chat.storeLine(t, "WEB", r.conversationId(), clientOrNull(a), r.message(), in);
            webBurst.add(t, r.conversationId(), clientOrNull(a), stored.messageId(), in.refs().isEmpty() ? null : in.refs().get(0), r.message().trim());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conversationId", stored.conversationId());
            m.put("messageId", stored.messageId());
            m.put("userMessageId", stored.messageId());
            m.put("pending", true);
            return m;
        }
        return view(chat.reply(t, "WEB", r.conversationId(), clientOrNull(a), r.message(), null, in));
    }

    /**
     * A photo or voice note from the website chat: read into text by the media readers (DeepSeek, then Qwen), then answered like a typed message. The file itself is not
     * kept; the chat keeps what it showed or said. {@code heard} is that text, so the page can show what the assistant understood.
     */
    @PostMapping(value = "/api/v1/shop/chat/media", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> sendMedia(@RequestParam String conversationId, @RequestParam MultipartFile file, @RequestParam(required = false) String message,
                                         Authentication a, HttpServletRequest req) throws java.io.IOException {
        UUID t = StoreContext.openTenantId();
        if (conversationId == null || !CONV_ID.matcher(conversationId).matches()) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid conversation");
        if (!chat.enabled(t, "WEB")) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_DISABLED", "Live chat is not available right now");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(java.util.Locale.ROOT);
        MediaReader.Kind kind = type.startsWith("image/") ? MediaReader.Kind.IMAGE : type.startsWith("audio/") || type.startsWith("video/webm") || type.startsWith("video/mp4") ? MediaReader.Kind.AUDIO : null;
        if (kind == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Send a photo or a voice note");
        if (file.getSize() > (kind == MediaReader.Kind.IMAGE ? QwenMediaReader.MAX_IMAGE : QwenMediaReader.MAX_AUDIO)) throw BusinessException.badRequest("VALIDATION_ERROR", "The file is too large");
        if (!media.enabled() || !chat.hasPoints(t)) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE", "Photos and voice notes are not available right now");
        messageLimits(t, conversationId, req, null);
        mediaLimit(conversationId);
        byte[] bytes = file.getBytes();
        String caption = message == null || message.isBlank() ? null : message.trim();
        // DR-B: the burst waits while the file is read (like the messaging channels), so «بكام ده؟» typed right after the photo is answered together with it
        var heldMeta = webBurst.enabled() ? new WebBurst.Meta(0, null) : null;
        java.util.function.Consumer<String> release = heldMeta == null ? null : webBurst.hold(t, conversationId, clientOrNull(a), heldMeta);
        String heard;
        try { heard = media.read(t, "WEB", new MediaReader.Media(kind, () -> bytes, type, caption)); }
        catch (RuntimeException e) { if (release != null) release.accept(null); throw e; }
        if (heard != null && heard.length() > 1000) heard = heard.substring(0, 1000);
        if (release != null && heard != null && !heard.isBlank()) {
            try {
                var stored = chat.storeLine(t, "WEB", conversationId, clientOrNull(a), heard, ChatService.ReplyInput.NONE);
                heldMeta.id = stored.messageId();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("conversationId", stored.conversationId());
                m.put("messageId", stored.messageId());
                m.put("userMessageId", stored.messageId());
                m.put("pending", true);
                m.put("heard", heard);
                release.accept(heard);
                return m;
            } catch (RuntimeException e) { release.accept(null); throw e; }
        }
        if (release != null) release.accept(null);
        if (heard == null || heard.isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conversationId", conversationId);
            m.put("reply", "ما قدرتش أفتح الملف ده 🙏 ممكن تكتبلي اللي محتاجه؟ / I couldn't open that file, could you type what you need?");
            m.put("order", null);
            m.put("products", List.of());
            m.put("heard", "");
            return m;
        }
        if (heard.length() > 1000) heard = heard.substring(0, 1000);
        Map<String, Object> out = view(chat.reply(t, "WEB", conversationId, clientOrNull(a), heard));
        out.put("heard", heard);
        return out;
    }

    // ---- shop dashboard --------------------------------------------------------------------------------------

    @GetMapping("/api/v1/store/ai/settings")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> settings() { return chat.getSettings(StoreContext.tenantId()); }

    @PutMapping("/api/v1/store/ai/settings")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> saveSettings(@RequestBody SettingsRequest r, Authentication a) {
        return chat.saveSettings(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.enabled(), r.tone(), r.maxDiscountPct(), r.greeting(), r.channels(), r.agentName(), r.agentAvatar());
    }

    /** How ready the shop's data is for the assistant: a score and what to fill in. */
    @GetMapping("/api/v1/store/ai/readiness")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> readiness() { return readiness.readiness(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/ai/lessons")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> lessons() { return chat.lessons(StoreContext.tenantId()); }

    @PostMapping("/api/v1/store/ai/lessons")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addLesson(@RequestBody LessonRequest r, Authentication a) {
        return chat.addLesson(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.instruction(), r.exampleQuestion(), r.exampleAnswer());
    }

    @DeleteMapping("/api/v1/store/ai/lessons/{id}")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable UUID id, Authentication a) { chat.deleteLesson(StoreContext.tenantId(), (UUID) a.getPrincipal(), id); }

    /** The owner plays the customer. Nothing is ordered for real in this chat. */
    @PostMapping("/api/v1/store/ai/test")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> test(@RequestBody TestRequest r, Authentication a, HttpServletRequest req) {
        UUID user = (UUID) a.getPrincipal();
        throttle.check("chat-test|" + user, 120, Duration.ofHours(1));
        var in = r.replyToMessageId() == null ? ChatService.ReplyInput.NONE : new ChatService.ReplyInput(List.of(ReplyRef.ofId(r.replyToMessageId())), List.of());
        return view(chat.reply(StoreContext.tenantId(), "TEST", user.toString(), null, r.message(), null, in));
    }

    @GetMapping("/api/v1/store/ai/test")
    @PreAuthorize("hasAuthority('ai.manage')")
    public List<Map<String, Object>> testHistory(Authentication a) { return chat.history(StoreContext.tenantId(), "TEST", a.getPrincipal().toString()); }

    @DeleteMapping("/api/v1/store/ai/test")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTest(Authentication a) { chat.reset(StoreContext.tenantId(), "TEST", a.getPrincipal().toString()); }

    // ---- platform admin: lessons for every shop --------------------------------------------------------------

    @GetMapping("/api/v1/platform/chat/lessons")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public List<Map<String, Object>> globalLessons() { return chat.lessons(null); }

    @PostMapping("/api/v1/platform/chat/lessons")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addGlobalLesson(@RequestBody LessonRequest r, Authentication a) {
        return chat.addLesson(null, (UUID) a.getPrincipal(), r.instruction(), r.exampleQuestion(), r.exampleAnswer());
    }

    @DeleteMapping("/api/v1/platform/chat/lessons/{id}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGlobalLesson(@PathVariable UUID id, Authentication a) { chat.deleteLesson(null, (UUID) a.getPrincipal(), id); }

    // ---- platform admin: training room (chat with a shop's assistant as a customer, turn replies into lessons) ----

    public record TrainSend(String message, String model) {}
    public record TrainDraft(String customerMessage, String agentReply, String remark) {}

    private static String trainer(Authentication a) { return "admin-" + a.getPrincipal(); }

    @GetMapping("/api/v1/platform/chat/train/shops")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public List<Map<String, Object>> trainShops() { return chat.trainShops(); }

    @GetMapping("/api/v1/platform/chat/train/{tenantId}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> trainHistory(@PathVariable UUID tenantId, Authentication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messages", chat.history(tenantId, "TEST", trainer(a)));
        m.put("model", chat.testModel(tenantId, trainer(a)) == null ? "default" : chat.testModel(tenantId, trainer(a)));
        return m;
    }

    @PostMapping("/api/v1/platform/chat/train/{tenantId}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> trainSend(@PathVariable UUID tenantId, @RequestBody TrainSend r, Authentication a) {
        throttle.check("chat-train|" + a.getPrincipal(), 300, Duration.ofHours(1));
        chat.setTestModel(tenantId, trainer(a), r.model());
        return view(chat.reply(tenantId, "TEST", trainer(a), null, r.message()));
    }

    @DeleteMapping("/api/v1/platform/chat/train/{tenantId}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trainReset(@PathVariable UUID tenantId, Authentication a) { chat.reset(tenantId, "TEST", trainer(a)); }

    @PostMapping("/api/v1/platform/chat/train/draft-lesson")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> trainDraft(@RequestBody TrainDraft r, Authentication a) {
        throttle.check("chat-train-draft|" + a.getPrincipal(), 120, Duration.ofHours(1));
        return chat.draftLesson(r.customerMessage(), r.agentReply(), r.remark());
    }

    @GetMapping("/api/v1/platform/chat/train/{tenantId}/lessons")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public List<Map<String, Object>> trainShopLessons(@PathVariable UUID tenantId) { return chat.lessons(tenantId); }

    @PostMapping("/api/v1/platform/chat/train/{tenantId}/lessons")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> trainAddShopLesson(@PathVariable UUID tenantId, @RequestBody LessonRequest r, Authentication a) {
        return chat.addLesson(tenantId, (UUID) a.getPrincipal(), r.instruction(), r.exampleQuestion(), r.exampleAnswer());
    }

    public record EvalStart(String model, String only) {}

    /** Plays the golden conversations against this shop on a model and grades them (see AiEvalService); poll the run for the scores. */
    @PostMapping("/api/v1/platform/chat/train/{tenantId}/eval")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> evalStart(@PathVariable UUID tenantId, @RequestBody EvalStart r, Authentication a) {
        throttle.check("chat-eval|" + a.getPrincipal(), 20, Duration.ofHours(1));
        return Map.of("id", eval.start(tenantId, r.model(), r.only()));
    }

    @GetMapping("/api/v1/platform/chat/train/eval/{id}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> evalRun(@PathVariable UUID id) { return eval.run(id); }

    @DeleteMapping("/api/v1/platform/chat/train/{tenantId}/lessons/{id}")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trainDeleteShopLesson(@PathVariable UUID tenantId, @PathVariable UUID id, Authentication a) { chat.deleteLesson(tenantId, (UUID) a.getPrincipal(), id); }
}

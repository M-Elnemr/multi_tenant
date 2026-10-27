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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Storefront chat (customers, guests included), the owner's test chat and teaching, and the platform admin's global lessons. */
@RestController
public class ChatController {
    private static final Pattern CONV_ID = Pattern.compile("^[0-9a-f-]{36}$");
    private final ChatService chat;
    private final LoginThrottle throttle;
    private final AiEvalService eval;

    public ChatController(ChatService chat, LoginThrottle throttle, AiEvalService eval) {
        this.chat = chat;
        this.throttle = throttle;
        this.eval = eval;
    }

    public record SendRequest(String conversationId, String message) {}
    public record TestRequest(String message) {}
    public record SettingsRequest(Boolean enabled, String tone, Integer maxDiscountPct, String greeting) {}
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
        return m;
    }

    // ---- storefront ------------------------------------------------------------------------------------------

    /** Public: tells the storefront whether to show the live-chat button. */
    @GetMapping("/api/v1/shop/chat/config")
    public Map<String, Object> config() {
        UUID t = StoreContext.tenantId();
        var s = chat.getSettings(t);
        return Map.of("enabled", chat.available() && Boolean.TRUE.equals(s.get("enabled")), "greeting", s.get("greeting") == null ? "" : s.get("greeting"));
    }

    @GetMapping("/api/v1/shop/chat/history")
    public List<Map<String, Object>> history(@RequestParam String conversationId) {
        if (!CONV_ID.matcher(conversationId).matches()) return List.of();
        return chat.history(StoreContext.tenantId(), "WEB", conversationId);
    }

    @PostMapping("/api/v1/shop/chat")
    public Map<String, Object> send(@RequestBody SendRequest r, Authentication a, HttpServletRequest req) {
        UUID t = StoreContext.openTenantId();
        if (r.conversationId() == null || !CONV_ID.matcher(r.conversationId()).matches()) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid conversation");
        if (!chat.enabled(t)) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_DISABLED", "Live chat is not available right now");
        throttle.check("chat-ip|" + ClientInfo.ip(req), 40, Duration.ofMinutes(10));
        throttle.check("chat-conv|" + r.conversationId(), 300, Duration.ofDays(1));
        return view(chat.reply(t, "WEB", r.conversationId(), clientOrNull(a), r.message()));
    }

    // ---- shop dashboard --------------------------------------------------------------------------------------

    @GetMapping("/api/v1/store/ai/settings")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> settings() { return chat.getSettings(StoreContext.tenantId()); }

    @PutMapping("/api/v1/store/ai/settings")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> saveSettings(@RequestBody SettingsRequest r, Authentication a) {
        return chat.saveSettings(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.enabled(), r.tone(), r.maxDiscountPct(), r.greeting());
    }

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
        return view(chat.reply(StoreContext.tenantId(), "TEST", user.toString(), null, r.message()));
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

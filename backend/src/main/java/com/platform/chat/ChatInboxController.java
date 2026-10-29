package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The shop's chat inbox: read the assistant's chats, answer a customer yourself, pause or resume the assistant in one chat. */
@RestController
@RequestMapping("/api/v1/store/chat/conversations")
public class ChatInboxController {
    private final ChatInboxService inbox;

    public ChatInboxController(ChatInboxService inbox) { this.inbox = inbox; }

    public record ReplyRequest(String message) {}
    public record PauseRequest(Integer hours) {}

    @GetMapping
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean needsYou) { return inbox.list(StoreContext.tenantId(), needsYou); }

    @GetMapping("/needs-you-count")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> needsYouCount() { return Map.of("count", inbox.needsYouCount(StoreContext.tenantId())); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> get(@PathVariable UUID id) { return inbox.get(StoreContext.tenantId(), id); }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> reply(@PathVariable UUID id, @RequestBody ReplyRequest r, Authentication a) { return inbox.reply(StoreContext.tenantId(), (UUID) a.getPrincipal(), id, r.message()); }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> pause(@PathVariable UUID id, @RequestBody(required = false) PauseRequest r, Authentication a) {
        return inbox.pause(StoreContext.tenantId(), (UUID) a.getPrincipal(), id, r == null ? null : r.hours());
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> resume(@PathVariable UUID id, Authentication a) { return inbox.resume(StoreContext.tenantId(), (UUID) a.getPrincipal(), id); }

    @PostMapping("/{id}/done")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> done(@PathVariable UUID id) { return inbox.done(StoreContext.tenantId(), id); }
}

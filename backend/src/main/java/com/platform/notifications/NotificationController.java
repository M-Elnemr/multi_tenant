package com.platform.notifications;

import com.platform.shared.Page;
import com.platform.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    private static UUID tenant() { var c = TenantContext.get(); return c == null ? null : c.id(); }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize, Authentication a) {
        return service.list((UUID) a.getPrincipal(), tenant(), unread, Page.of(page, pageSize));
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unread(Authentication a) { return Map.of("count", service.unreadCount((UUID) a.getPrincipal(), tenant())); }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@PathVariable UUID id, Authentication a) {
        service.markRead((UUID) a.getPrincipal(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(Authentication a) {
        service.markAllRead((UUID) a.getPrincipal(), tenant());
        return Map.of("ok", true);
    }
}

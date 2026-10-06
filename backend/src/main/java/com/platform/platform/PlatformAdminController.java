package com.platform.platform;

import com.platform.shared.Page;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {
    private final PlatformAdminService service;

    public PlatformAdminController(PlatformAdminService service) { this.service = service; }

    public record StatusRequest(String status, String reason) {}

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> overview() { return service.overview(); }

    @GetMapping("/tenants")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> tenants(@RequestParam(required = false) String type, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return service.tenants(type, status, q, Page.of(page, pageSize));
    }

    @GetMapping("/tenants/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> tenant(@PathVariable UUID id) { return service.tenant(id); }

    @PostMapping("/tenants/{id}/status")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusRequest r, Authentication a) { return service.setStatus((UUID) a.getPrincipal(), id, r.status(), r.reason()); }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('platform.audit.read')")
    public Map<String, Object> audit(@RequestParam(required = false) UUID tenantId, @RequestParam(required = false) String action, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return service.audit(tenantId, action, Page.of(page, pageSize));
    }
}

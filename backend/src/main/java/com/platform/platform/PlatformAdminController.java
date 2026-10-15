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
    private final TenantPurgeService purge;
    private final com.platform.billing.PayRequestService payRequests;

    public PlatformAdminController(PlatformAdminService service, TenantPurgeService purge, com.platform.billing.PayRequestService payRequests) {
        this.service = service;
        this.purge = purge;
        this.payRequests = payRequests;
    }

    public record StatusRequest(String status, String reason) {}
    public record PlanRequest(String planCode, Integer days) {}
    public record PurgeRequest(String confirmSlug) {}
    public record PayHandleRequest(String status, String adminNote, Boolean activate) {}

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> overview() { return service.overview(); }

    @GetMapping("/tenants")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> tenants(@RequestParam(required = false) String type, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return service.tenants(type, status, q, filter, Page.of(page, pageSize));
    }

    @GetMapping("/tenants/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> tenant(@PathVariable UUID id) { return service.tenant(id); }

    @PostMapping("/tenants/{id}/status")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusRequest r, Authentication a) { return service.setStatus((UUID) a.getPrincipal(), id, r.status(), r.reason()); }

    @PostMapping("/tenants/{id}/plan")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> plan(@PathVariable UUID id, @RequestBody PlanRequest r, Authentication a) { return service.changePlan((UUID) a.getPrincipal(), id, r.planCode(), r.days()); }

    /** Deletes the business and all its data for good. Platform owner only; the body must repeat the business address name. */
    @DeleteMapping("/tenants/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.purge')")
    public Map<String, Object> delete(@PathVariable UUID id, @RequestBody PurgeRequest r, Authentication a) { return purge.purge((UUID) a.getPrincipal(), id, r.confirmSlug()); }

    @GetMapping("/pay-requests")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> payRequests(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return payRequests.adminList(status, Page.of(page, pageSize));
    }

    @PostMapping("/pay-requests/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> handlePayRequest(@PathVariable UUID id, @RequestBody PayHandleRequest r, Authentication a) {
        return payRequests.handle((UUID) a.getPrincipal(), id, r.status(), r.adminNote(), r.activate() == null || r.activate());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('platform.audit.read')")
    public Map<String, Object> audit(@RequestParam(required = false) UUID tenantId, @RequestParam(required = false) String action, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return service.audit(tenantId, action, Page.of(page, pageSize));
    }
}

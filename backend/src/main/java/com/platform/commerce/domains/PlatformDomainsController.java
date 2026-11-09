package com.platform.commerce.domains;

import com.platform.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Platform-admin side (mirrors {@code PlatformAdminController}'s style): cross-tenant, explicit tenantId/orderId params, never the caller's own tenant context. */
@RestController
@RequestMapping("/api/v1/platform/domains")
public class PlatformDomainsController {
    private final DomainAdminService admin;

    public PlatformDomainsController(DomainAdminService admin) { this.admin = admin; }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> orders(@RequestParam(required = false) String status, @RequestParam(required = false) UUID tenantId, @RequestParam(required = false) String q,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return admin.orders(status, tenantId, q, Page.of(page, pageSize));
    }

    @GetMapping("/price-rules")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public List<Map<String, Object>> priceRules() { return admin.priceRules(); }

    @PutMapping("/price-rules")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> savePriceRule(@RequestBody DomainPricingService.RuleReq r) { return admin.savePriceRule(r); }

    @PostMapping("/orders/{id}/retry")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> retry(@PathVariable UUID id, Authentication a) { return admin.retry((UUID) a.getPrincipal(), id); }

    @GetMapping("/provider-errors")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public List<Map<String, Object>> providerErrors(@RequestParam(required = false) Integer limit) { return admin.providerErrors(limit == null ? 100 : limit); }
}

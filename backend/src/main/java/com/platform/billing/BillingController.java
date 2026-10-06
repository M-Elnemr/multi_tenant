package com.platform.billing;

import com.platform.shared.BusinessException;
import com.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingService billing;

    public BillingController(BillingService billing) { this.billing = billing; }

    public record ChangePlanRequest(@NotBlank String planCode, String provider) {}

    /** Public: pricing page. On a tenant host only that tenant type's plans are returned. */
    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        var t = TenantContext.get();
        return billing.plans(t == null ? null : t.type());
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('billing.read')")
    public Map<String, Object> subscription() { return billing.subscription(TenantContext.require().id()); }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing.read')")
    public List<Map<String, Object>> invoices() { return billing.invoices(TenantContext.require().id()); }

    @PostMapping("/subscription/change")
    @PreAuthorize("hasAuthority('billing.manage')")
    public Map<String, Object> change(@Valid @RequestBody ChangePlanRequest r, Authentication a) {
        var t = TenantContext.require();
        return billing.changePlan(t.id(), (UUID) a.getPrincipal(), t.type(), r.planCode(), r.provider() == null ? "mock" : r.provider());
    }

    @PostMapping("/subscription/cancel")
    @PreAuthorize("hasAuthority('billing.manage')")
    public Map<String, Object> cancel(Authentication a) {
        billing.cancel(TenantContext.require().id(), (UUID) a.getPrincipal());
        return Map.of("ok", true);
    }

    /** Provider callback: authenticated by signature, not by user session. Duplicate deliveries are acknowledged and ignored. */
    @PostMapping("/webhooks/{provider}")
    public Map<String, Object> webhook(@PathVariable String provider, @RequestBody String raw,
                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        if (raw == null || raw.isBlank()) throw BusinessException.badRequest("INVALID_WEBHOOK", "Empty body");
        return Map.of("result", billing.handleWebhook(provider, raw, signature).name());
    }
}

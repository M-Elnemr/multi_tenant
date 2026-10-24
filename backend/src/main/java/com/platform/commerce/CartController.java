package com.platform.commerce;

import com.platform.core.auth.LoginThrottle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Storefront cart capture (public) and the owner's incomplete carts screen. */
@RestController
public class CartController {
    public record SnapshotReq(String clientKey, String name, String phone, List<CartRecoveryService.Line> items) {}
    public record SettingsReq(Boolean recoveryEnabled, Boolean allowPaid, Integer remindAfterHours, String couponCode, String sendMode) {}

    private final CartRecoveryService carts;
    private final CodRiskService risk;
    private final LoginThrottle throttle;

    public CartController(CartRecoveryService carts, CodRiskService risk, LoginThrottle throttle) {
        this.carts = carts;
        this.risk = risk;
        this.throttle = throttle;
    }

    @PostMapping("/api/v1/shop/carts/snapshot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void snapshot(@RequestBody SnapshotReq r, Authentication a) {
        throttle.check("cart-snap|" + (r.clientKey() == null ? "x" : r.clientKey()), 60, Duration.ofHours(1));
        UUID user = a != null && a.getPrincipal() instanceof UUID u ? u : null;
        carts.snapshot(StoreContext.tenantId(), r.clientKey(), user, r.name(), r.phone(), r.items());
    }

    @GetMapping("/api/v1/store/carts")
    @PreAuthorize("hasAuthority('cart.read') or hasAuthority('cart.manage')")
    public Map<String, Object> list(@RequestParam(required = false) String view) { return carts.list(StoreContext.tenantId(), view); }

    @PutMapping("/api/v1/store/carts/settings")
    @PreAuthorize("hasAuthority('cart.manage')")
    public Map<String, Object> settings(@RequestBody SettingsReq r, Authentication a) { return carts.saveSettings(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.recoveryEnabled(), r.allowPaid(), r.remindAfterHours(), r.couponCode(), r.sendMode()); }

    @PostMapping("/api/v1/store/carts/{id}/remind")
    @PreAuthorize("hasAuthority('cart.manage')")
    public Map<String, Object> remind(@PathVariable UUID id, Authentication a) { return carts.sendReminder(StoreContext.tenantId(), (UUID) a.getPrincipal(), id); }

    @PostMapping("/api/v1/store/carts/{id}/contacted")
    @PreAuthorize("hasAuthority('cart.manage')")
    public Map<String, Object> contacted(@PathVariable UUID id) { return carts.markContacted(StoreContext.tenantId(), id); }

    @PostMapping("/api/v1/store/carts/{id}/dismiss")
    @PreAuthorize("hasAuthority('cart.manage')")
    public Map<String, Object> dismiss(@PathVariable UUID id) { return carts.dismiss(StoreContext.tenantId(), id); }

    @PostMapping("/api/v1/store/carts/template")
    @PreAuthorize("hasAuthority('cart.manage')")
    public Map<String, Object> template() { return carts.registerTemplate(StoreContext.tenantId()); }

    // ---- COD guard ----------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/store/cod-guard")
    @PreAuthorize("hasAuthority('order.read')")
    public Map<String, Object> guard() { return risk.summary(StoreContext.tenantId()); }

    public record GuardReq(Boolean holdHighRisk, Boolean holdMediumRisk) {}

    @PutMapping("/api/v1/store/cod-guard")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> saveGuard(@RequestBody GuardReq r, Authentication a) { return risk.saveSettings(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.holdHighRisk(), r.holdMediumRisk()); }
}

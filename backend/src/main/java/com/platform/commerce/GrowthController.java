package com.platform.commerce;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Wave 2 growth tools: loyalty points, bought-together, segments and broadcasts, product feeds and server-side tracking. */
@RestController
public class GrowthController {
    public record LoyaltySettingsReq(Boolean enabled, Integer earnPercent, Integer minRedeem) {}
    public record RedeemReq(Integer points) {}
    public record BroadcastReq(String segment, String message, java.util.UUID imageFileId, String mode, Boolean push, String pushTitle, Boolean onlyConsented) {}
    public record CapiReq(Boolean enabled, String token) {}

    private static final String MANAGE = "hasAuthority('growth.manage')";

    private final LoyaltyService loyalty;
    private final UpsellService upsell;
    private final SegmentService segments;
    private final FeedService feed;
    private final CapiService capi;

    public GrowthController(LoyaltyService loyalty, UpsellService upsell, SegmentService segments, FeedService feed, CapiService capi) {
        this.loyalty = loyalty;
        this.upsell = upsell;
        this.segments = segments;
        this.feed = feed;
        this.capi = capi;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    // ---- storefront ---------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/shop/products/{slug}/together")
    public List<Map<String, Object>> together(@PathVariable String slug) { return upsell.together(StoreContext.tenantId(), slug); }

    @GetMapping("/api/v1/shop/feed")
    public Map<String, Object> feed() { return feed.feed(StoreContext.tenantId()); }

    @GetMapping("/api/v1/shop/loyalty")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> mine(Authentication a) { return loyalty.mine(StoreContext.tenantId(), user(a)); }

    @PostMapping("/api/v1/shop/loyalty/redeem")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> redeem(@RequestBody RedeemReq r, Authentication a) { return loyalty.redeem(StoreContext.tenantId(), user(a), r.points() == null ? 0 : r.points()); }

    // ---- dashboard ----------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/store/loyalty")
    @PreAuthorize(MANAGE)
    public Map<String, Object> loyaltyAdmin() { return Map.of("settings", loyalty.settings(StoreContext.tenantId()), "top", loyalty.top(StoreContext.tenantId())); }

    @PutMapping("/api/v1/store/loyalty")
    @PreAuthorize(MANAGE)
    public Map<String, Object> saveLoyalty(@RequestBody LoyaltySettingsReq r, Authentication a) { return loyalty.saveSettings(StoreContext.tenantId(), user(a), r.enabled(), r.earnPercent(), r.minRedeem()); }

    @GetMapping("/api/v1/store/segments")
    @PreAuthorize(MANAGE)
    public Map<String, Object> segments() { return segments.overview(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/segments/{key}")
    @PreAuthorize(MANAGE)
    public Map<String, Object> segment(@PathVariable String key) { return Map.of("customers", segments.customers(StoreContext.tenantId(), key), "preview", segments.preview(StoreContext.tenantId(), key)); }

    @PostMapping("/api/v1/store/broadcasts")
    @PreAuthorize(MANAGE)
    public Map<String, Object> broadcast(@RequestBody BroadcastReq r, Authentication a) { return segments.broadcast(StoreContext.tenantId(), user(a), new SegmentService.Req(r.segment(), r.message(), r.imageFileId(), r.mode() == null ? "PAID" : r.mode(), r.push(), r.pushTitle(), r.onlyConsented())); }

    @GetMapping("/api/v1/store/broadcasts")
    @PreAuthorize(MANAGE)
    public List<Map<String, Object>> broadcasts() { return segments.history(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/tracking")
    @PreAuthorize(MANAGE)
    public Map<String, Object> tracking() { return capi.settings(StoreContext.tenantId()); }

    @PutMapping("/api/v1/store/tracking")
    @PreAuthorize(MANAGE)
    public Map<String, Object> saveTracking(@RequestBody CapiReq r, Authentication a) { return capi.save(StoreContext.tenantId(), user(a), r.enabled(), r.token()); }
}

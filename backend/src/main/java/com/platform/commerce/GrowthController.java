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
    public record BroadcastReq(java.util.List<String> segments, java.util.List<String> platforms, String message, java.util.UUID imageFileId, Boolean push, String pushTitle, java.util.List<String> variants, java.util.List<String> manualPlatforms) {}
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
    public Map<String, Object> segments(@RequestParam(required = false) String platforms) {
        return segments.overview(StoreContext.tenantId(), SegmentService.parseSet(platforms, SegmentService.PLATFORMS, "platform"));
    }

    @GetMapping("/api/v1/store/segments/customers")
    @PreAuthorize(MANAGE)
    public Map<String, Object> segmentCustomers(@RequestParam(required = false) String segments, @RequestParam(required = false) String platforms, @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) String manual) {
        return this.segments.customers(StoreContext.tenantId(), SegmentService.parseSet(segments, SegmentService.SEGMENT_ORDER, "segment"), SegmentService.parseSet(platforms, SegmentService.PLATFORMS, "platform"), q, limit, offset,
                manual == null || manual.isBlank() ? java.util.Set.of() : SegmentService.parseSet(manual, java.util.List.of("FACEBOOK", "INSTAGRAM"), "platform"));
    }

    @PostMapping("/api/v1/store/broadcasts")
    @PreAuthorize(MANAGE)
    public Map<String, Object> broadcast(@RequestBody BroadcastReq r, Authentication a) {
        return segments.broadcast(StoreContext.tenantId(), user(a), new SegmentService.Req(r.segments(), r.platforms(), r.message(), r.imageFileId(), r.push(), r.pushTitle(), r.variants(), r.manualPlatforms()));
    }

    @GetMapping("/api/v1/store/broadcasts/{id}/recipients")
    @PreAuthorize(MANAGE)
    public Map<String, Object> broadcastRecipients(@PathVariable java.util.UUID id, @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return segments.recipients(StoreContext.tenantId(), id, status, limit, offset);
    }

    public record VariantsReq(String message, Integer count) {}

    @PostMapping("/api/v1/store/broadcasts/variants")
    @PreAuthorize(MANAGE)
    public Map<String, Object> variants(@RequestBody VariantsReq r) { return Map.of("variants", segments.suggestVariants(StoreContext.tenantId(), r.message(), r.count() == null ? 3 : r.count())); }

    @PostMapping("/api/v1/store/broadcasts/{id}/resume")
    @PreAuthorize(MANAGE)
    public Map<String, Object> resumeBroadcast(@PathVariable java.util.UUID id) { return segments.resume(StoreContext.tenantId(), id); }

    @PostMapping("/api/v1/store/broadcasts/{id}/cancel")
    @PreAuthorize(MANAGE)
    public Map<String, Object> cancelBroadcast(@PathVariable java.util.UUID id) { return segments.cancel(StoreContext.tenantId(), id); }

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

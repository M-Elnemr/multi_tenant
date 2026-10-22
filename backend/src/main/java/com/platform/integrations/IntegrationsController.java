package com.platform.integrations;

import com.platform.commerce.StoreContext;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Sales channels (marketplaces and other store platforms) of the shop behind the host. */
@RestController
@RequestMapping("/api/v1")
public class IntegrationsController {
    public record AccountPatch(String label, Map<String, String> credentials) {}
    public record StartReq(String country) {}
    public record SyncReq(String what) {}
    public record ImportReq(List<UUID> listingIds) {}
    public record LinkReq(UUID variantId) {}
    public record ActionReq(String action, Map<String, String> params) {}

    private final IntegrationService integrations;
    private final IntegrationOAuthService oauth;
    private final ListingService listings;
    private final ChannelOrderService orders;
    private final InsightsService insights;

    public IntegrationsController(IntegrationService integrations, IntegrationOAuthService oauth, ListingService listings, ChannelOrderService orders, InsightsService insights) {
        this.integrations = integrations;
        this.oauth = oauth;
        this.listings = listings;
        this.orders = orders;
        this.insights = insights;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    private static final String READ = "hasAuthority('integration.read') or hasAuthority('integration.manage')";
    private static final String WRITE = "hasAuthority('integration.manage')";

    // ---- accounts -------------------------------------------------------------------------------------------------

    @GetMapping("/store/integrations/catalog")
    @PreAuthorize(READ)
    public List<Map<String, Object>> catalog(@RequestParam(required = false) String country, @RequestParam(required = false) String kind) { return integrations.catalog(country, kind); }

    @GetMapping("/store/integrations/accounts")
    @PreAuthorize(READ)
    public List<Map<String, Object>> accounts(@RequestParam(required = false) String kind) { return integrations.accounts(StoreContext.tenantId(), kind); }

    @GetMapping("/store/integrations/accounts/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> account(@PathVariable UUID id) { return integrations.account(StoreContext.tenantId(), id); }

    @PostMapping("/store/integrations/accounts")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> connect(@RequestBody IntegrationService.ConnectReq r, Authentication a) { return integrations.connect(StoreContext.tenantId(), user(a), r); }

    @PatchMapping("/store/integrations/accounts/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody AccountPatch r, Authentication a) { return integrations.updateCredentials(StoreContext.tenantId(), user(a), id, r.label(), r.credentials()); }

    @DeleteMapping("/store/integrations/accounts/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> disconnect(@PathVariable UUID id, Authentication a) {
        integrations.disconnect(StoreContext.tenantId(), user(a), id);
        return Map.of("ok", true);
    }

    @PostMapping("/store/integrations/accounts/{id}/test")
    @PreAuthorize(WRITE)
    public Map<String, Object> test(@PathVariable UUID id) { return integrations.test(StoreContext.tenantId(), id); }

    /** Queues a refresh now (products, orders or both). */
    @PostMapping("/store/integrations/accounts/{id}/sync")
    @PreAuthorize(WRITE)
    public Map<String, Object> sync(@PathVariable UUID id, @RequestBody(required = false) SyncReq r) {
        UUID t = StoreContext.tenantId();
        var row = integrations.row(t, id);
        Providers.Def d = Providers.get((String) row.get("provider"));
        String what = r == null || r.what() == null ? "all" : r.what();
        if (!"orders".equals(what) && Providers.supports(d, "PRODUCTS")) integrations.enqueue(t, id, "IMPORT_PRODUCTS", Map.of());
        if (!"products".equals(what) && Providers.supports(d, "ORDERS")) integrations.enqueue(t, id, "PULL_ORDERS", Map.of("full", true));
        return Map.of("queued", true);
    }

    @GetMapping("/store/integrations/accounts/{id}/activity")
    @PreAuthorize(READ)
    public List<Map<String, Object>> activity(@PathVariable UUID id) {
        integrations.row(StoreContext.tenantId(), id);
        return insights.activity(StoreContext.tenantId(), id);
    }

    @PostMapping("/store/integrations/oauth/{provider}/start")
    @PreAuthorize(WRITE)
    public Map<String, Object> oauthStart(@PathVariable String provider, @RequestBody(required = false) StartReq r, Authentication a) {
        return Map.of("url", oauth.startUrl(StoreContext.tenantId(), user(a), provider, r == null ? null : r.country()));
    }

    /** The fixed address the platforms send the owner back to (public: the signed state says who is connecting). */
    @GetMapping("/integrations/oauth/{provider}/callback")
    public ResponseEntity<Void> oauthCallback(@PathVariable String provider, @RequestParam Map<String, String> params) {
        String to;
        try { to = oauth.callback(provider, new HashMap<>(params)); } catch (BusinessException e) { return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); }   // a forged or expired state goes nowhere
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(to).toString()).header("Cache-Control", "no-store").build();
    }

    // ---- listings -------------------------------------------------------------------------------------------------

    @GetMapping("/store/integrations/listings")
    @PreAuthorize(READ)
    public Map<String, Object> listings(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String q, @RequestParam(required = false) String filter,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return listings.list(StoreContext.tenantId(), accountId, q, filter, Page.of(page, pageSize));
    }

    @PatchMapping("/store/integrations/listings/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> editListing(@PathVariable UUID id, @RequestBody ListingService.Edit e, Authentication a) { return listings.edit(StoreContext.tenantId(), user(a), id, e); }

    @PostMapping("/store/integrations/listings/{id}/link")
    @PreAuthorize(WRITE)
    public Map<String, Object> link(@PathVariable UUID id, @RequestBody LinkReq r) { return listings.link(StoreContext.tenantId(), id, r.variantId()); }

    @PostMapping("/store/integrations/listings/{id}/unlink")
    @PreAuthorize(WRITE)
    public Map<String, Object> unlink(@PathVariable UUID id) {
        listings.unlink(StoreContext.tenantId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/store/integrations/accounts/{id}/import-to-shop")
    @PreAuthorize(WRITE)
    public Map<String, Object> importToShop(@PathVariable UUID id, @RequestBody ImportReq r, Authentication a) { return listings.importToShop(StoreContext.tenantId(), user(a), id, r.listingIds() == null ? List.of() : r.listingIds()); }

    @GetMapping("/store/integrations/publish-plan")
    @PreAuthorize(READ)
    public Map<String, Object> plan(@RequestParam UUID productId) { return listings.plan(StoreContext.tenantId(), productId); }

    @PostMapping("/store/integrations/publish")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> publish(@RequestBody ListingService.PublishReq r, Authentication a) { return listings.publish(StoreContext.tenantId(), user(a), r); }

    // ---- orders, customers, insights ------------------------------------------------------------------------------------

    @GetMapping("/store/integrations/orders")
    @PreAuthorize(READ)
    public Map<String, Object> orders(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String stage, @RequestParam(required = false) String q, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return orders.list(StoreContext.tenantId(), accountId, stage, q, from, to, Page.of(page, pageSize));
    }

    @GetMapping("/store/integrations/orders/summary")
    @PreAuthorize(READ)
    public Map<String, Object> orderSummary(@RequestParam(required = false) UUID accountId) { return orders.summary(StoreContext.tenantId(), accountId); }

    @GetMapping("/store/integrations/orders/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> order(@PathVariable UUID id) { return orders.detail(StoreContext.tenantId(), id); }

    @PostMapping("/store/integrations/orders/{id}/actions")
    @PreAuthorize(WRITE)
    public Map<String, Object> orderAction(@PathVariable UUID id, @RequestBody ActionReq r, Authentication a) { return orders.act(StoreContext.tenantId(), user(a), id, r.action(), r.params()); }

    @GetMapping("/store/integrations/customers")
    @PreAuthorize(READ)
    public Map<String, Object> customers(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return orders.customers(StoreContext.tenantId(), accountId, q, Page.of(page, pageSize));
    }

    @GetMapping("/store/integrations/insights")
    @PreAuthorize(READ)
    public Map<String, Object> insights(@RequestParam(defaultValue = "30") int days, @RequestParam(required = false) UUID accountId) { return insights.overview(StoreContext.tenantId(), days, accountId); }
}

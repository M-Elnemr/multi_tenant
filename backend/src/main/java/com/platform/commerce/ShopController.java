package com.platform.commerce;

import com.platform.billing.PaymentProvider;
import com.platform.billing.WebhookEventStore;
import com.platform.core.auth.TokenResponse;
import com.platform.shared.BusinessException;
import com.platform.shared.ClientInfo;
import com.platform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Storefront API: public catalog, shopper account, cart quote, checkout, own orders. The store comes from the Host. */
@RestController
@RequestMapping("/api/v1/shop")
public class ShopController {
    private final CatalogService catalog;
    private final StoreSearchService search;
    private final StoreSettingsService settings;
    private final com.platform.core.auth.LoginThrottle throttle;
    private final CheckoutService checkout;
    private final OrderService orders;
    private final ShopperService shopper;
    private final List<PaymentProvider> providers;
    private final WebhookEventStore webhooks;

    public ShopController(CatalogService catalog, StoreSearchService search, StoreSettingsService settings, com.platform.core.auth.LoginThrottle throttle, CheckoutService checkout,
                          OrderService orders, ShopperService shopper, List<PaymentProvider> providers, WebhookEventStore webhooks) {
        this.catalog = catalog;
        this.search = search;
        this.settings = settings;
        this.throttle = throttle;
        this.checkout = checkout;
        this.orders = orders;
        this.shopper = shopper;
        this.providers = providers;
        this.webhooks = webhooks;
    }

    public record ProfileRequest(String name, String phone) {}
    public record ReviewRequest(int rating, String text) {}
    public record CancelRequest(String reason) {}
    public record AddressRequest(String title, boolean makeDefault, CheckoutService.AddressReq address) {}

    // ---- public ----------------------------------------------------------------------------------------------

    @GetMapping("/branches")
    public List<Map<String, Object>> branches() { return settings.branches(StoreContext.tenantId(), true); }

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        UUID t = StoreContext.tenantId();
        return Map.of("profile", settings.publicProfile(t), "paymentMethods", settings.paymentMethods(t).stream().filter(m -> Boolean.TRUE.equals(m.get("enabled"))).toList(),
                "shippingMethods", settings.shippingMethods(t, true));
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() { return catalog.categories(StoreContext.tenantId(), true); }

    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                        @RequestParam(required = false) Long minPrice, @RequestParam(required = false) Long maxPrice, @RequestParam(required = false) String brand,
                                        @RequestParam(required = false) Boolean inStock, @RequestParam(required = false) Boolean onSale, @RequestParam(required = false) Integer minRating,
                                        @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return search.list(StoreContext.tenantId(), Page.of(page, pageSize), new StoreSearchService.Filter(q, category, minPrice, maxPrice, brand, inStock, onSale, minRating, sort));
    }

    @GetMapping("/products/facets")
    public Map<String, Object> facets(@RequestParam(required = false) String q, @RequestParam(required = false) String category) {
        return search.facets(StoreContext.tenantId(), new StoreSearchService.Filter(q, category, null, null, null, null, null, null, null));
    }

    @GetMapping("/search/suggest")
    public Map<String, Object> suggest(@RequestParam String q) { return search.suggest(StoreContext.tenantId(), q); }

    @GetMapping("/home")
    public Map<String, Object> home() { return search.home(StoreContext.tenantId()); }

    @GetMapping("/products/{slug}/related")
    public List<Map<String, Object>> related(@PathVariable String slug) {
        UUID t = StoreContext.tenantId();
        return catalog.related(t, (UUID) catalog.detailBySlug(t, slug).get("id"), 8);
    }

    @GetMapping("/products/{slug}")
    public Map<String, Object> product(@PathVariable String slug) {
        UUID t = StoreContext.tenantId();
        Map<String, Object> p = catalog.detailBySlug(t, slug);
        p.put("reviews", shopper.publicReviews(t, (UUID) p.get("id")));
        return p;
    }

    // ---- shopper ----------------------------------------------------------------------------------------------

    /** The signed-in client's id, or null for a guest (guests browse, price a cart and order without an account). */
    private static UUID clientOrNull(Authentication a) {
        boolean client = a != null && a.getPrincipal() instanceof UUID && a.getAuthorities().stream().anyMatch(x -> "ROLE_CUSTOMER".equals(x.getAuthority()));
        return client ? (UUID) a.getPrincipal() : null;
    }

    @PostMapping("/cart/quote")
    public Map<String, Object> quote(@RequestBody CheckoutService.CheckoutReq r) {
        return checkout.quoteView(StoreContext.tenantId(), null, r);
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> checkout(@RequestBody CheckoutService.CheckoutReq r, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication a, HttpServletRequest req) {
        UUID t = StoreContext.openTenantId();
        UUID client = clientOrNull(a);
        if (client == null) throttle.check("guestorder|" + ClientInfo.ip(req), 20, java.time.Duration.ofMinutes(10));
        try {
            return checkout.placeOrder(t, client, r, key);
        } catch (DataIntegrityViolationException e) {
            // Two identical requests raced on the same Idempotency-Key: the loser returns the winner's order.
            Map<String, Object> prior = key == null ? null : checkout.existingByKey(t, key);
            if (prior != null) return prior;
            throw e;
        }
    }

    @GetMapping("/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> myOrders(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize, Authentication a) {
        return orders.list(StoreContext.tenantId(), Page.of(page, pageSize), null, (UUID) a.getPrincipal());
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> myOrder(@PathVariable UUID id, Authentication a) {
        return orders.getForCustomer(StoreContext.tenantId(), (UUID) a.getPrincipal(), id);
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) CancelRequest r, Authentication a) {
        UUID user = (UUID) a.getPrincipal();
        orders.changeStatus(StoreContext.tenantId(), id, "CANCELLED", null, r == null ? null : r.reason(), user);
        return orders.getForCustomer(StoreContext.tenantId(), user, id);
    }

    @GetMapping("/wishlist")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<Map<String, Object>> wishlist(Authentication a) { return shopper.wishlist(StoreContext.tenantId(), (UUID) a.getPrincipal()); }

    @PutMapping("/wishlist/{productId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> wishlistAdd(@PathVariable UUID productId, Authentication a) {
        shopper.wishlistAdd(StoreContext.tenantId(), (UUID) a.getPrincipal(), productId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/wishlist/{productId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> wishlistRemove(@PathVariable UUID productId, Authentication a) {
        shopper.wishlistRemove(StoreContext.tenantId(), (UUID) a.getPrincipal(), productId);
        return Map.of("ok", true);
    }

    @PostMapping("/products/{productId}/reviews")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> review(@PathVariable UUID productId, @RequestBody ReviewRequest r, Authentication a) {
        shopper.review(StoreContext.tenantId(), (UUID) a.getPrincipal(), productId, r.rating(), r.text());
        return Map.of("status", "PENDING");
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> me(Authentication a) { return shopper.profile((UUID) a.getPrincipal()); }

    /** The client keeps their own name and mobile on their account so the next order is pre-filled. */
    @PutMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> updateMe(@RequestBody ProfileRequest r, Authentication a) { return shopper.updateProfile((UUID) a.getPrincipal(), r.name(), r.phone()); }

    @GetMapping("/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<Map<String, Object>> addresses(Authentication a) { return shopper.addresses((UUID) a.getPrincipal()); }

    @PostMapping("/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addAddress(@RequestBody AddressRequest r, Authentication a) {
        return shopper.addAddress((UUID) a.getPrincipal(), r.address(), r.title(), r.makeDefault());
    }

    // ---- provider webhook (signature-authenticated, idempotent) ---------------------------------------------------

    @PostMapping("/webhooks/{provider}")
    public Map<String, Object> webhook(@PathVariable String provider, @RequestBody String raw, @RequestHeader(value = "X-Signature", required = false) String signature) {
        UUID tenantId = StoreContext.tenantId();
        PaymentProvider p = providers.stream().filter(x -> x.code().equals(provider)).findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Unknown provider"));
        if (!p.verifySignature(raw, signature)) throw BusinessException.unauthorized("INVALID_SIGNATURE", "Invalid webhook signature");
        Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(raw);
        if (body.get("eventId") == null || body.get("orderId") == null) throw BusinessException.badRequest("INVALID_WEBHOOK", "eventId and orderId are required");
        String key = "shop:" + provider;
        String eventId = String.valueOf(body.get("eventId"));
        if (!webhooks.firstDelivery(key, eventId, raw)) return Map.of("result", "DUPLICATE");
        String result = "IGNORED";
        if ("payment.succeeded".equals(body.get("type"))) {
            long amount = ((Number) body.get("amountMinor")).longValue();
            result = orders.markCardPaid(tenantId, UUID.fromString(String.valueOf(body.get("orderId"))), amount, (String) body.get("providerPaymentId")) ? "PROCESSED" : "DUPLICATE";
        }
        webhooks.markProcessed(key, eventId, result);
        return Map.of("result", result);
    }
}

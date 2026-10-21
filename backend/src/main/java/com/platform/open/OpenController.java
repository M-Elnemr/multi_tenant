package com.platform.open;

import com.platform.core.auth.LoginThrottle;
import com.platform.shared.ClientInfo;
import com.platform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The Open Store API, served on open.&lt;root-domain&gt; (no shop in the host). Browsing is public; checkout works as a guest or with a marketplace account
 * (X-Open-Token). Every order lands in the shop it was bought from.
 */
@RestController
@RequestMapping("/api/v1/open")
public class OpenController {
    public record GoogleRequest(String credential) {}
    public record ProfileRequest(String name, String phone) {}
    public record ReviewRequest(UUID productId, int rating, String text) {}
    public record TrackRequest(String shop, String orderNumber, String phone) {}

    private final OpenCatalogService catalog;
    private final OpenAccountService accounts;
    private final OpenCheckoutService checkout;
    private final LoginThrottle throttle;

    public OpenController(OpenCatalogService catalog, OpenAccountService accounts, OpenCheckoutService checkout, LoginThrottle throttle) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.checkout = checkout;
        this.throttle = throttle;
    }

    // ---- browse ------------------------------------------------------------------------------------------------------

    @GetMapping("/home")
    public Map<String, Object> home() { return catalog.home(); }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() { return catalog.categoryTree(); }

    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(required = false) String q, @RequestParam(required = false) String category, @RequestParam(required = false) String shop,
                                        @RequestParam(required = false) Long minPrice, @RequestParam(required = false) Long maxPrice, @RequestParam(required = false) String brand,
                                        @RequestParam(required = false) Boolean inStock, @RequestParam(required = false) Boolean onSale, @RequestParam(required = false) Boolean freeShipping,
                                        @RequestParam(required = false) Integer minRating, @RequestParam(required = false) String audience, @RequestParam(required = false) String color,
                                        @RequestParam(required = false) String size, @RequestParam(required = false) String condition, @RequestParam(required = false) String governorate,
                                        @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return catalog.list(Page.of(page, pageSize), new OpenCatalogService.Filter(q, category, shop, minPrice, maxPrice, brand, inStock, onSale, freeShipping, minRating, audience, color, size, condition, governorate, sort));
    }

    @GetMapping("/products/facets")
    public Map<String, Object> facets(@RequestParam(required = false) String q, @RequestParam(required = false) String category, @RequestParam(required = false) String shop) {
        return catalog.facets(new OpenCatalogService.Filter(q, category, shop, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @GetMapping("/products/{shopSlug}/{productSlug}")
    public Map<String, Object> product(@PathVariable String shopSlug, @PathVariable String productSlug) { return catalog.product(shopSlug, productSlug); }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam String q) { return catalog.suggest(q); }

    @GetMapping("/shops")
    public Map<String, Object> shops(@RequestParam(required = false) String q, @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return catalog.shops(q, Page.of(page, pageSize), sort);
    }

    @GetMapping("/shops/{slug}")
    public Map<String, Object> shop(@PathVariable String slug) { return catalog.shop(slug); }

    // ---- cart and checkout -----------------------------------------------------------------------------------------------

    @PostMapping("/cart/quote")
    public Map<String, Object> quote(@RequestBody OpenCheckoutService.CartReq r) { return checkout.quote(r); }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> place(@RequestBody OpenCheckoutService.CartReq r, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestHeader(value = "X-Open-Token", required = false) String token, HttpServletRequest req) {
        UUID account = accounts.accountOf(token);
        if (account == null) throttle.check("open-guest-order|" + ClientInfo.ip(req), 20, Duration.ofMinutes(10));
        return checkout.place(account, r, key);
    }

    /** A guest follows an order with the shop, the order number and the phone it was placed with. */
    @PostMapping("/orders/track")
    public Map<String, Object> track(@RequestBody TrackRequest r, HttpServletRequest req) {
        throttle.check("open-track|" + ClientInfo.ip(req), 30, Duration.ofMinutes(10));
        return checkout.track(r.shop(), r.orderNumber(), r.phone());
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestHeader(value = "X-Open-Token", required = false) String token, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return checkout.history(accounts.require(token), Page.of(page, pageSize));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> order(@PathVariable UUID id, @RequestHeader(value = "X-Open-Token", required = false) String token) { return checkout.order(accounts.require(token), id); }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> review(@RequestBody ReviewRequest r, @RequestHeader(value = "X-Open-Token", required = false) String token) {
        checkout.review(accounts.require(token), r.productId(), r.rating(), r.text());
        return Map.of("status", "PENDING");
    }

    // ---- account (the shops' Google clients) ----------------------------------------------------------------------------------

    @GetMapping("/auth/config")
    public Map<String, Object> authConfig() { return accounts.config(); }

    @PostMapping("/auth/google")
    public Map<String, Object> google(@RequestBody GoogleRequest r, HttpServletRequest req) { return accounts.googleLogin(r.credential(), ClientInfo.ip(req)); }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Open-Token", required = false) String token) {
        accounts.logout(token);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Open-Token", required = false) String token) { return accounts.profile(accounts.require(token)); }

    @PutMapping("/me")
    public Map<String, Object> updateMe(@RequestBody ProfileRequest r, @RequestHeader(value = "X-Open-Token", required = false) String token) { return accounts.update(accounts.require(token), r.name(), r.phone()); }

    @DeleteMapping("/me")
    public Map<String, Object> deleteMe(@RequestHeader(value = "X-Open-Token", required = false) String token) {
        accounts.delete(accounts.require(token));
        return Map.of("ok", true);
    }
}

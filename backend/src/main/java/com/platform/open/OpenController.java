package com.platform.open;

import com.platform.commerce.ShopperService;
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
    public record RegisterRequest(String name, String phone, String password) {}
    public record LoginRequest(String phone, String password) {}
    public record ProfileRequest(String name) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}
    public record ReviewRequest(UUID productId, int rating, String text) {}

    private final OpenCatalogService catalog;
    private final OpenAccountService accounts;
    private final OpenCheckoutService checkout;
    private final ShopperService shopper;
    private final LoginThrottle throttle;

    public OpenController(OpenCatalogService catalog, OpenAccountService accounts, OpenCheckoutService checkout, ShopperService shopper, LoginThrottle throttle) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.checkout = checkout;
        this.shopper = shopper;
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

    /** A guest opens a purchase with its id and the phone used; a signed-in shopper just needs the id. */
    @GetMapping("/orders/{groupId}")
    public Map<String, Object> track(@PathVariable UUID groupId, @RequestParam(required = false) String phone, @RequestHeader(value = "X-Open-Token", required = false) String token, HttpServletRequest req) {
        throttle.check("open-track|" + ClientInfo.ip(req), 60, Duration.ofMinutes(10));
        return checkout.track(groupId, accounts.accountOf(token), phone);
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestHeader(value = "X-Open-Token", required = false) String token, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return checkout.history(accounts.require(token), Page.of(page, pageSize));
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> review(@RequestBody ReviewRequest r, @RequestHeader(value = "X-Open-Token", required = false) String token) {
        checkout.review(accounts.require(token), r.productId(), r.rating(), r.text(), shopper);
        return Map.of("status", "PENDING");
    }

    // ---- account ---------------------------------------------------------------------------------------------------------------

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest r, HttpServletRequest req) { return accounts.register(r.name(), r.phone(), r.password(), ClientInfo.ip(req)); }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest r, HttpServletRequest req) { return accounts.login(r.phone(), r.password(), ClientInfo.ip(req)); }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Open-Token", required = false) String token) {
        accounts.logout(token);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Open-Token", required = false) String token) { return accounts.profile(accounts.require(token)); }

    @PutMapping("/me")
    public Map<String, Object> updateMe(@RequestBody ProfileRequest r, @RequestHeader(value = "X-Open-Token", required = false) String token) { return accounts.update(accounts.require(token), r.name()); }

    @PostMapping("/me/password")
    public Map<String, Object> password(@RequestBody PasswordRequest r, @RequestHeader(value = "X-Open-Token", required = false) String token) {
        accounts.changePassword(accounts.require(token), r.currentPassword(), r.newPassword());
        return Map.of("ok", true);
    }

    @DeleteMapping("/me")
    public Map<String, Object> deleteMe(@RequestHeader(value = "X-Open-Token", required = false) String token) {
        accounts.delete(accounts.require(token));
        return Map.of("ok", true);
    }
}

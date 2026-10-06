package com.platform.commerce;

import com.platform.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Merchant dashboard API. Every method is permission-gated and scoped to the store resolved from the Host. */
@RestController
@RequestMapping("/api/v1/store")
public class StoreAdminController {
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final OrderService orders;
    private final StoreSettingsService settings;
    private final ReportService reports;

    public StoreAdminController(CatalogService catalog, InventoryService inventory, OrderService orders, StoreSettingsService settings, ReportService reports) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.settings = settings;
        this.reports = reports;
    }

    public record CategoryRequest(String name, UUID parentId, String description, Integer sortOrder, Boolean active) {}
    public record ProductPatch(String name, String description, String shortDescription, String brand, UUID categoryId, String status) {}
    public record VariantPatch(Long priceMinor, Long compareAtPriceMinor, String status) {}
    public record AdjustRequest(UUID branchId, UUID variantId, int delta, String type, String reason) {}
    public record StatusRequest(String status, String reason) {}
    public record RefundRequest(long amountMinor, String reason) {}
    public record BranchRequest(String name, String code, String phone, String address, String city) {}
    public record PaymentMethodRequest(String method, boolean enabled) {}
    public record ShippingRequest(String type, String name, long feeMinor, Long freeAboveMinor) {}
    public record ActiveRequest(boolean active) {}
    public record ModerateRequest(String status) {}

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    // ---- catalog -------------------------------------------------------------------------------------------

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('category.manage') or hasAuthority('product.update')")
    public List<Map<String, Object>> categories() { return catalog.categories(StoreContext.tenantId(), false); }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('category.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@RequestBody CategoryRequest r) {
        return catalog.createCategory(StoreContext.tenantId(), r.name(), r.parentId(), r.description(), r.sortOrder() == null ? 0 : r.sortOrder());
    }

    @PatchMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('category.manage')")
    public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody CategoryRequest r) {
        catalog.updateCategory(StoreContext.tenantId(), id, r.name(), r.description(), r.active(), r.sortOrder());
        return Map.of("ok", true);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('product.update') or hasAuthority('product.create')")
    public Map<String, Object> products(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return catalog.adminList(StoreContext.tenantId(), Page.of(page, pageSize), q, status);
    }

    @PostMapping("/products")
    @PreAuthorize("hasAuthority('product.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProduct(@RequestBody CatalogService.ProductReq r, Authentication a) {
        return catalog.createProduct(StoreContext.tenantId(), user(a), r);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('product.update') or hasAuthority('product.create')")
    public Map<String, Object> product(@PathVariable UUID id) { return catalog.detail(StoreContext.tenantId(), id, false); }

    @PatchMapping("/products/{id}")
    @PreAuthorize("hasAuthority('product.update')")
    public Map<String, Object> updateProduct(@PathVariable UUID id, @RequestBody ProductPatch r, Authentication a) {
        return catalog.updateProduct(StoreContext.tenantId(), user(a), id, r.name(), r.description(), r.shortDescription(), r.brand(), r.categoryId(), r.status());
    }

    @PatchMapping("/variants/{id}")
    @PreAuthorize("hasAuthority('product.update')")
    public Map<String, Object> updateVariant(@PathVariable UUID id, @RequestBody VariantPatch r, Authentication a) {
        catalog.updateVariant(StoreContext.tenantId(), user(a), id, r.priceMinor(), r.compareAtPriceMinor(), r.status());
        return Map.of("ok", true);
    }

    @DeleteMapping("/products/{id}")
    @PreAuthorize("hasAuthority('product.delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id, Authentication a) { catalog.archive(StoreContext.tenantId(), user(a), id); }

    // ---- inventory ---------------------------------------------------------------------------------------------

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('inventory.adjust')")
    public Map<String, Object> inventory(@RequestParam(defaultValue = "false") boolean lowStock, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return inventory.list(StoreContext.tenantId(), Page.of(page, pageSize), lowStock);
    }

    @PostMapping("/inventory/adjustments")
    @PreAuthorize("hasAuthority('inventory.adjust')")
    public Map<String, Object> adjust(@RequestBody AdjustRequest r, Authentication a) {
        return inventory.adjust(StoreContext.tenantId(), user(a), r.branchId(), r.variantId(), r.delta(), r.type() == null ? "ADJUSTMENT" : r.type(), r.reason());
    }

    // ---- orders --------------------------------------------------------------------------------------------------

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('order.read')")
    public Map<String, Object> orders(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return orders.list(StoreContext.tenantId(), Page.of(page, pageSize), status, null);
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('order.read')")
    public Map<String, Object> order(@PathVariable UUID id) {
        Map<String, Object> o = orders.get(StoreContext.tenantId(), id);
        o.remove("userId");
        return o;
    }

    @PostMapping("/orders/{id}/status")
    @PreAuthorize("hasAuthority('order.update_status')")
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusRequest r, Authentication a) {
        return orders.changeStatus(StoreContext.tenantId(), id, r.status(), user(a), r.reason(), null);
    }

    @PostMapping("/orders/{id}/refund")
    @PreAuthorize("hasAuthority('refund.create')")
    public Map<String, Object> refund(@PathVariable UUID id, @RequestBody RefundRequest r, Authentication a) {
        return orders.refund(StoreContext.tenantId(), user(a), id, r.amountMinor(), r.reason());
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('customer.read')")
    public Map<String, Object> customers(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return settings.customers(StoreContext.tenantId(), Page.of(page, pageSize));
    }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasAuthority('report.read')")
    public Map<String, Object> summary() { return reports.summary(StoreContext.tenantId()); }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> profile() { return settings.profile(StoreContext.tenantId()); }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> r, Authentication a) { return settings.updateProfile(StoreContext.tenantId(), user(a), r); }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('branch.manage') or hasAuthority('inventory.adjust')")
    public List<Map<String, Object>> branches() { return settings.branches(StoreContext.tenantId()); }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('branch.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBranch(@RequestBody BranchRequest r, Authentication a) {
        return settings.createBranch(StoreContext.tenantId(), user(a), r.name(), r.code(), r.phone(), r.address(), r.city());
    }

    @GetMapping("/payment-methods")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> paymentMethods() { return settings.paymentMethods(StoreContext.tenantId()); }

    @PutMapping("/payment-methods")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> setPaymentMethod(@RequestBody PaymentMethodRequest r, Authentication a) {
        return settings.setPaymentMethod(StoreContext.tenantId(), user(a), r.method(), r.enabled());
    }

    @GetMapping("/shipping-methods")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> shipping() { return settings.shippingMethods(StoreContext.tenantId(), false); }

    @PostMapping("/shipping-methods")
    @PreAuthorize("hasAuthority('shipping.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createShipping(@RequestBody ShippingRequest r, Authentication a) {
        return settings.createShipping(StoreContext.tenantId(), user(a), r.type(), r.name(), r.feeMinor(), r.freeAboveMinor());
    }

    @PutMapping("/shipping-methods/{id}/active")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public Map<String, Object> shippingActive(@PathVariable UUID id, @RequestBody ActiveRequest r) {
        settings.setShippingActive(StoreContext.tenantId(), id, r.active());
        return Map.of("ok", true);
    }

    @GetMapping("/coupons")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public List<Map<String, Object>> coupons() { return settings.coupons(StoreContext.tenantId()); }

    @PostMapping("/coupons")
    @PreAuthorize("hasAuthority('coupon.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCoupon(@RequestBody StoreSettingsService.CouponReq r, Authentication a) { return settings.createCoupon(StoreContext.tenantId(), user(a), r); }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('review.moderate')")
    public Map<String, Object> reviews(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return settings.reviewsForModeration(StoreContext.tenantId(), Page.of(page, pageSize), status);
    }

    @PostMapping("/reviews/{id}/status")
    @PreAuthorize("hasAuthority('review.moderate')")
    public Map<String, Object> moderate(@PathVariable UUID id, @RequestBody ModerateRequest r, Authentication a) {
        settings.moderateReview(StoreContext.tenantId(), user(a), id, r.status());
        return Map.of("ok", true);
    }
}

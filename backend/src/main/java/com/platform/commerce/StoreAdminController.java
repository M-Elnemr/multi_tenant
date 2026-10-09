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
    private final StoreFulfillmentService fulfilment;
    private final ProductCsvService csv;
    private final StoreSettingsService settings;
    private final ReportService reports;

    public StoreAdminController(CatalogService catalog, InventoryService inventory, OrderService orders, StoreSettingsService settings, ReportService reports, StoreFulfillmentService fulfilment, ProductCsvService csv) {
        this.fulfilment = fulfilment;
        this.csv = csv;
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.settings = settings;
        this.reports = reports;
    }

    public record CategoryRequest(String name, UUID parentId, String description, Integer sortOrder, Boolean active, Boolean moveToParent, UUID imageFileId) {}
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
        catalog.updateCategory(StoreContext.tenantId(), id, r.name(), r.description(), r.active(), r.sortOrder(), r.parentId(), Boolean.TRUE.equals(r.moveToParent()), r.imageFileId());
        return Map.of("ok", true);
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('category.manage')")
    public Map<String, Object> deleteCategory(@PathVariable UUID id) {
        catalog.deleteCategory(StoreContext.tenantId(), id);
        return Map.of("ok", true);
    }

    @PatchMapping("/products/{id}/extras")
    @PreAuthorize("hasAuthority('product.update')")
    public Map<String, Object> productExtras(@PathVariable UUID id, @RequestBody Map<String, Object> r, Authentication a) { return catalog.updateExtras(StoreContext.tenantId(), user(a), id, r); }

    @GetMapping("/products/export.csv")
    @PreAuthorize("hasAuthority('product.update') or hasAuthority('product.create')")
    public org.springframework.http.ResponseEntity<String> exportProducts() {
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8")).header("Content-Disposition", "attachment; filename=\"products.csv\"").body(csv.export(StoreContext.tenantId()));
    }

    public record ImportRequest(String csv, UUID branchId) {}

    @PostMapping("/products/import")
    @PreAuthorize("hasAuthority('product.create')")
    public Map<String, Object> importProducts(@RequestBody ImportRequest r, Authentication a) { return csv.importCsv(StoreContext.tenantId(), user(a), r.csv(), r.branchId()); }

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
    public Map<String, Object> orders(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) Boolean unconfirmed, @RequestParam(required = false) String governorate, @RequestParam(required = false) String q) {
        return orders.list(StoreContext.tenantId(), Page.of(page, pageSize), status, null, unconfirmed, governorate, q);
    }

    /** Counter for the dashboard badge: new orders nobody has called yet. */
    @GetMapping("/orders/summary")
    @PreAuthorize("hasAuthority('order.read')")
    public Map<String, Object> ordersSummary() { return Map.of("unconfirmed", orders.unconfirmedCount(StoreContext.tenantId())); }

    @PostMapping("/orders/{id}/confirm")
    @PreAuthorize("hasAuthority('order.update_status')")
    public Map<String, Object> confirm(@PathVariable UUID id, Authentication a) { return fulfilment.confirm(StoreContext.tenantId(), user(a), id); }

    public record TrackingRequest(String courierName, String trackingNumber, String trackingUrl) {}

    @PatchMapping("/orders/{id}/tracking")
    @PreAuthorize("hasAuthority('order.update_status')")
    public Map<String, Object> tracking(@PathVariable UUID id, @RequestBody TrackingRequest r, Authentication a) { return fulfilment.setTracking(StoreContext.tenantId(), user(a), id, r.courierName(), r.trackingNumber(), r.trackingUrl()); }

    @GetMapping("/returns")
    @PreAuthorize("hasAuthority('order.read')")
    public Map<String, Object> returns(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return fulfilment.returns(StoreContext.tenantId(), Page.of(page, pageSize), status);
    }

    public record ReturnDecision(String status, String note) {}

    @PostMapping("/returns/{id}/decision")
    @PreAuthorize("hasAuthority('order.update_status')")
    public Map<String, Object> decideReturn(@PathVariable UUID id, @RequestBody ReturnDecision r, Authentication a) { return fulfilment.decideReturn(StoreContext.tenantId(), user(a), id, r.status(), r.note()); }

    public record BlockRequest(String phone, boolean blocked, String note) {}

    @GetMapping("/phone-flags")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<Map<String, Object>> phoneFlags() { return fulfilment.flaggedPhones(StoreContext.tenantId()); }

    @PostMapping("/phone-flags")
    @PreAuthorize("hasAuthority('order.update_status')")
    public Map<String, Object> blockPhone(@RequestBody BlockRequest r, Authentication a) { return fulfilment.blockPhone(StoreContext.tenantId(), user(a), r.phone(), r.blocked(), r.note()); }

    @GetMapping("/shipping-zones")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> zones() { return fulfilment.zones(StoreContext.tenantId(), false); }

    @PostMapping("/shipping-zones")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> addZone(@RequestBody Map<String, Object> r, Authentication a) { return fulfilment.saveZone(StoreContext.tenantId(), user(a), null, r); }

    @PatchMapping("/shipping-zones/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> editZone(@PathVariable UUID id, @RequestBody Map<String, Object> r, Authentication a) { return fulfilment.saveZone(StoreContext.tenantId(), user(a), id, r); }

    @DeleteMapping("/shipping-zones/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public List<Map<String, Object>> deleteZone(@PathVariable UUID id, Authentication a) { return fulfilment.deleteZone(StoreContext.tenantId(), user(a), id); }

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
    public Map<String, Object> customers(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String q) {
        return settings.customers(StoreContext.tenantId(), Page.of(page, pageSize), q);
    }

    /** A client's orders. Read-only: there is deliberately no way for a store to create or edit clients. */
    @GetMapping("/customers/{id}/orders")
    @PreAuthorize("hasAuthority('customer.read')")
    public java.util.List<Map<String, Object>> customerOrders(@PathVariable UUID id) { return settings.customerOrders(StoreContext.tenantId(), id); }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasAuthority('report.read')")
    public Map<String, Object> summary() { return reports.summary(StoreContext.tenantId()); }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> profile() { return settings.profile(StoreContext.tenantId()); }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> r, Authentication a) {
        // The profile read includes read-only extras (categories list); only plain text fields are editable here (categories have their own endpoint).
        return settings.updateProfile(StoreContext.tenantId(), user(a), r);
    }

    public record CategoriesRequest(List<String> categories, String otherCategory) {}

    /** The category list shops pick from (same one offered at sign-up). */
    @GetMapping("/business-categories")
    @PreAuthorize("hasAuthority('settings.manage')")
    public List<Map<String, Object>> categoryList() { return settings.categories(); }

    @PutMapping("/profile/categories")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateCategories(@RequestBody CategoriesRequest r, Authentication a) { return settings.updateCategories(StoreContext.tenantId(), user(a), r.categories(), r.otherCategory()); }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('branch.manage') or hasAuthority('inventory.adjust')")
    public List<Map<String, Object>> branches() { return settings.branches(StoreContext.tenantId()); }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('branch.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBranch(@RequestBody Map<String, Object> r, Authentication a) { return settings.createBranch(StoreContext.tenantId(), user(a), r); }

    @PatchMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('branch.manage')")
    public Map<String, Object> updateBranch(@PathVariable UUID id, @RequestBody Map<String, Object> r, Authentication a) { return settings.updateBranch(StoreContext.tenantId(), user(a), id, r); }

    @GetMapping("/banners")
    @PreAuthorize("hasAuthority('settings.manage')")
    public List<Map<String, Object>> banners() { return settings.banners(StoreContext.tenantId()); }

    @PostMapping("/banners")
    @PreAuthorize("hasAuthority('settings.manage')")
    public List<Map<String, Object>> addBanner(@RequestBody Map<String, Object> r, Authentication a) { return settings.saveBanner(StoreContext.tenantId(), user(a), null, r); }

    @PatchMapping("/banners/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public List<Map<String, Object>> editBanner(@PathVariable UUID id, @RequestBody Map<String, Object> r, Authentication a) { return settings.saveBanner(StoreContext.tenantId(), user(a), id, r); }

    @DeleteMapping("/banners/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public List<Map<String, Object>> deleteBanner(@PathVariable UUID id) { return settings.deleteBanner(StoreContext.tenantId(), id); }

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

    public record ShippingPatch(String name, Long feeMinor, Long freeAboveMinor) {}

    @PatchMapping("/shipping-methods/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public Map<String, Object> updateShipping(@PathVariable UUID id, @RequestBody ShippingPatch r, Authentication a) { return settings.updateShipping(StoreContext.tenantId(), user(a), id, r.name(), r.feeMinor(), r.freeAboveMinor()); }

    @DeleteMapping("/shipping-methods/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public Map<String, Object> deleteShipping(@PathVariable UUID id, Authentication a) { return settings.deleteShipping(StoreContext.tenantId(), user(a), id); }

    @PutMapping("/coupons/{id}/active")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public Map<String, Object> couponActive(@PathVariable UUID id, @RequestBody ActiveRequest r, Authentication a) { return settings.setCouponActive(StoreContext.tenantId(), user(a), id, r.active()); }

    @DeleteMapping("/coupons/{id}")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public Map<String, Object> deleteCoupon(@PathVariable UUID id, Authentication a) { return settings.deleteCoupon(StoreContext.tenantId(), user(a), id); }

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

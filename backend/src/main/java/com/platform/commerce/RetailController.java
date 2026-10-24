package com.platform.commerce;

import com.platform.shared.Page;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Wave 3 back-office: purchasing, stock transfers, point of sale, invoices, exports, reports, API keys, webhooks, blog, translations and the country preset. */
@RestController
@RequestMapping("/api/v1/store")
public class RetailController {
    public record TransferReq(UUID variantId, UUID fromBranch, UUID toBranch, Integer quantity, String note) {}
    public record ReceiveReq(UUID branchId) {}
    public record DraftReq(UUID supplierId) {}
    public record ReasonReq(String reason) {}
    public record NameReq(String name) {}
    public record WebhookReq(String url, List<String> events) {}
    public record ActiveReq(Boolean active) {}
    public record TranslationReq(String name, String description) {}
    public record CountryReq(String country) {}

    private static final String PURCHASE = "hasAuthority('purchase.manage')";
    private static final String POS = "hasAuthority('pos.use')";
    private static final String INVOICE = "hasAuthority('invoice.manage')";
    private static final String REPORT = "hasAuthority('report.read')";
    private static final String API = "hasAuthority('api.manage')";
    private static final String BLOG = "hasAuthority('blog.manage')";

    private final PurchasingService purchasing;
    private final InventoryService inventory;
    private final PosService pos;
    private final InvoiceService invoices;
    private final ExportService exports;
    private final ReportsPlusService reports;
    private final ApiKeyService keys;
    private final WebhookService webhooks;
    private final BlogService blog;
    private final TranslationService translations;
    private final MarketService market;
    private final ReturnService returns;

    public RetailController(PurchasingService purchasing, InventoryService inventory, PosService pos, InvoiceService invoices, ExportService exports, ReportsPlusService reports,
                            ApiKeyService keys, WebhookService webhooks, BlogService blog, TranslationService translations, MarketService market, ReturnService returns) {
        this.purchasing = purchasing;
        this.inventory = inventory;
        this.pos = pos;
        this.invoices = invoices;
        this.exports = exports;
        this.reports = reports;
        this.keys = keys;
        this.webhooks = webhooks;
        this.blog = blog;
        this.translations = translations;
        this.market = market;
        this.returns = returns;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    private static UUID t() { return StoreContext.tenantId(); }

    // ---- suppliers, purchase orders, transfers ---------------------------------------------------------------------

    @GetMapping("/suppliers")
    @PreAuthorize(PURCHASE)
    public List<Map<String, Object>> suppliers() { return purchasing.suppliers(t()); }

    @PostMapping("/suppliers")
    @PreAuthorize(PURCHASE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addSupplier(@RequestBody PurchasingService.SupplierReq r, Authentication a) { return purchasing.saveSupplier(t(), user(a), null, r); }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> saveSupplier(@PathVariable UUID id, @RequestBody PurchasingService.SupplierReq r, Authentication a) { return purchasing.saveSupplier(t(), user(a), id, r); }

    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize(PURCHASE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSupplier(@PathVariable UUID id) { purchasing.deleteSupplier(t(), id); }

    @GetMapping("/purchase-orders")
    @PreAuthorize(PURCHASE)
    public List<Map<String, Object>> purchaseOrders() { return purchasing.list(t()); }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> purchaseOrder(@PathVariable UUID id) { return purchasing.get(t(), id); }

    @PostMapping("/purchase-orders")
    @PreAuthorize(PURCHASE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPo(@RequestBody PurchasingService.PoReq r, Authentication a) { return purchasing.create(t(), user(a), r); }

    @PostMapping("/purchase-orders/draft-low-stock")
    @PreAuthorize(PURCHASE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> draftPo(@RequestBody(required = false) DraftReq r, Authentication a) { return purchasing.draftFromLowStock(t(), user(a), r == null ? null : r.supplierId()); }

    @PostMapping("/purchase-orders/{id}/ordered")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> ordered(@PathVariable UUID id, Authentication a) { return purchasing.markOrdered(t(), user(a), id); }

    @PostMapping("/purchase-orders/{id}/receive")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody(required = false) ReceiveReq r, Authentication a) { return purchasing.receive(t(), user(a), id, r == null ? null : r.branchId()); }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> cancelPo(@PathVariable UUID id, Authentication a) { return purchasing.cancel(t(), user(a), id); }

    @PostMapping("/inventory/transfer")
    @PreAuthorize(PURCHASE)
    public Map<String, Object> transfer(@RequestBody TransferReq r, Authentication a) { return inventory.transfer(t(), user(a), r.variantId(), r.fromBranch(), r.toBranch(), r.quantity() == null ? 0 : r.quantity(), r.note()); }

    @GetMapping("/inventory/transfers")
    @PreAuthorize(PURCHASE)
    public List<Map<String, Object>> transfers() { return inventory.transfers(t()); }

    // ---- point of sale ----------------------------------------------------------------------------------------------

    @GetMapping("/pos/lookup")
    @PreAuthorize(POS)
    public Map<String, Object> lookup(@RequestParam String code) { return pos.lookup(t(), code); }

    @PostMapping("/pos/sales")
    @PreAuthorize(POS)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> sale(@RequestBody PosService.SaleReq r, Authentication a) { return pos.sale(t(), user(a), r); }

    @GetMapping("/pos/labels")
    @PreAuthorize("hasAuthority('pos.use') or hasAuthority('product.update')")
    public List<Map<String, Object>> labels(@RequestParam(required = false) String q) { return pos.labels(t(), q); }

    @GetMapping("/pos/search")
    @PreAuthorize(POS)
    public List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) { return pos.search(t(), q, limit == null ? 30 : limit); }

    @GetMapping("/pos/returns/order")
    @PreAuthorize(POS)
    public Map<String, Object> returnOrder(@RequestParam String q) { return returns.findOrder(t(), q); }

    @PostMapping("/pos/returns")
    @PreAuthorize("hasAuthority('pos.use') and hasAuthority('refund.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReturn(@RequestBody ReturnService.ReturnReq r, Authentication a) { return returns.create(t(), user(a), r); }

    @GetMapping("/pos/returns")
    @PreAuthorize(POS)
    public List<Map<String, Object>> recentReturns() { return returns.recent(t()); }

    @GetMapping("/pos/today")
    @PreAuthorize(POS)
    public Map<String, Object> today() { return pos.today(t()); }

    // ---- invoices -----------------------------------------------------------------------------------------------------

    @PostMapping("/orders/{id}/invoice")
    @PreAuthorize(INVOICE)
    public Map<String, Object> invoice(@PathVariable UUID id, Authentication a) { return invoices.issue(t(), user(a), id); }

    @PostMapping("/orders/{id}/credit-note")
    @PreAuthorize(INVOICE)
    public Map<String, Object> creditNote(@PathVariable UUID id, @RequestBody(required = false) ReasonReq r, Authentication a) { return invoices.creditNote(t(), user(a), id, r == null ? null : r.reason()); }

    @GetMapping("/orders/{id}/invoices")
    @PreAuthorize(INVOICE)
    public List<Map<String, Object>> orderInvoices(@PathVariable UUID id) { return invoices.forOrder(t(), id); }

    @GetMapping("/invoices")
    @PreAuthorize(INVOICE)
    public Map<String, Object> invoiceList(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) { return invoices.list(t(), Page.of(page, pageSize)); }

    @GetMapping("/invoices/{id}")
    @PreAuthorize(INVOICE)
    public Map<String, Object> invoiceGet(@PathVariable UUID id) { return invoices.get(t(), id); }

    // ---- exports and reports ---------------------------------------------------------------------------------------

    private static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(body.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/export/orders.csv")
    @PreAuthorize(REPORT)
    public ResponseEntity<byte[]> exportOrders(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return csv("orders.csv", exports.orders(t(), from, to)); }

    @GetMapping("/export/customers.csv")
    @PreAuthorize(REPORT)
    public ResponseEntity<byte[]> exportCustomers() { return csv("customers.csv", exports.customers(t())); }

    @GetMapping("/reports/overview")
    @PreAuthorize(REPORT)
    public Map<String, Object> overview(@RequestParam(required = false, defaultValue = "30") int days) { return reports.overview(t(), days); }

    @GetMapping("/reports/margin")
    @PreAuthorize(REPORT)
    public Map<String, Object> margin(@RequestParam(required = false, defaultValue = "30") int days) { return reports.margin(t(), days); }

    @GetMapping("/reports/cohorts")
    @PreAuthorize(REPORT)
    public List<Map<String, Object>> cohorts() { return reports.cohorts(t()); }

    // ---- API keys and webhooks --------------------------------------------------------------------------------------

    @GetMapping("/api-keys")
    @PreAuthorize(API)
    public List<Map<String, Object>> apiKeys() { return keys.list(t()); }

    @PostMapping("/api-keys")
    @PreAuthorize(API)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createKey(@RequestBody NameReq r, Authentication a) { return keys.create(t(), user(a), r.name()); }

    @DeleteMapping("/api-keys/{id}")
    @PreAuthorize(API)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable UUID id, Authentication a) { keys.revoke(t(), user(a), id); }

    @GetMapping("/webhooks")
    @PreAuthorize(API)
    public List<Map<String, Object>> webhooks() { return webhooks.list(t()); }

    @PostMapping("/webhooks")
    @PreAuthorize(API)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createWebhook(@RequestBody WebhookReq r, Authentication a) { return webhooks.create(t(), user(a), r.url(), r.events()); }

    @PutMapping("/webhooks/{id}/active")
    @PreAuthorize(API)
    public Map<String, Object> webhookActive(@PathVariable UUID id, @RequestBody ActiveReq r) { return webhooks.setActive(t(), id, Boolean.TRUE.equals(r.active())); }

    @GetMapping("/webhooks/{id}/deliveries")
    @PreAuthorize(API)
    public List<Map<String, Object>> deliveries(@PathVariable UUID id) { return webhooks.deliveries(t(), id); }

    @DeleteMapping("/webhooks/{id}")
    @PreAuthorize(API)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWebhook(@PathVariable UUID id, Authentication a) { webhooks.delete(t(), user(a), id); }

    // ---- blog and translations -------------------------------------------------------------------------------------

    @GetMapping("/blog")
    @PreAuthorize(BLOG)
    public List<Map<String, Object>> blogList() { return blog.adminList(t()); }

    @GetMapping("/blog/{id}")
    @PreAuthorize(BLOG)
    public Map<String, Object> blogGet(@PathVariable UUID id) { return blog.adminGet(t(), id); }

    @PostMapping("/blog")
    @PreAuthorize(BLOG)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> blogCreate(@RequestBody BlogService.Post p, Authentication a) { return blog.save(t(), user(a), null, p); }

    @PutMapping("/blog/{id}")
    @PreAuthorize(BLOG)
    public Map<String, Object> blogSave(@PathVariable UUID id, @RequestBody BlogService.Post p, Authentication a) { return blog.save(t(), user(a), id, p); }

    @DeleteMapping("/blog/{id}")
    @PreAuthorize(BLOG)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void blogDelete(@PathVariable UUID id, Authentication a) { blog.delete(t(), user(a), id); }

    @GetMapping("/translations")
    @PreAuthorize(BLOG)
    public List<Map<String, Object>> translationOverview() { return translations.overview(t()); }

    @PutMapping("/translations/{productId}")
    @PreAuthorize(BLOG)
    public Map<String, Object> translate(@PathVariable UUID productId, @RequestBody TranslationReq r, Authentication a) { return translations.save(t(), user(a), productId, "en", r.name(), r.description()); }

    @PostMapping("/translations/draft")
    @PreAuthorize(BLOG)
    public Map<String, Object> draftTranslations(Authentication a) { return Map.of("drafted", translations.draftMissing(t(), user(a), 10)); }

    // ---- country preset ---------------------------------------------------------------------------------------------

    @GetMapping("/market")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> marketGet() { return market.get(t()); }

    @PutMapping("/market")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> marketApply(@RequestBody CountryReq r, Authentication a) { return market.apply(t(), user(a), r.country()); }
}

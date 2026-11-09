package com.platform.commerce.domains;

import com.platform.shared.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Buy a new domain without leaving the dashboard: search -> registrant contact -> order (wallet-funded) -> the
 * setup-progress stepper polls {@code GET /orders/{id}}. Base path is {@code /api/v1/store/domains} per the shared
 * contract even though, like the existing connect flow, it applies to every tenant type (STORE/CLINIC/LAWYER) -
 * the tenant comes from the Host, exactly like {@code DomainController}.
 */
@RestController
@RequestMapping("/api/v1/store/domains")
public class StoreDomainsController {
    private final QuoteService quotes;
    private final DomainPricingService pricing;
    private final RegistrantContactService contacts;
    private final DomainOrderService orders;

    public StoreDomainsController(QuoteService quotes, DomainPricingService pricing, RegistrantContactService contacts, DomainOrderService orders) {
        this.quotes = quotes;
        this.pricing = pricing;
        this.contacts = contacts;
        this.orders = orders;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> search(@RequestParam String q) { return quotes.search(TenantContext.require().id(), q); }

    @GetMapping("/pricing")
    @PreAuthorize("hasAuthority('domains.purchase') or hasAuthority('domain.read') or hasAuthority('domain.manage')")
    public List<Map<String, Object>> pricing() { return pricing.pricing(); }

    @GetMapping("/registrant-contacts")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public List<Map<String, Object>> listContacts() { return contacts.list(TenantContext.require().id()); }

    @PostMapping("/registrant-contacts")
    @PreAuthorize("hasAuthority('domains.purchase')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addContact(@RequestBody RegistrantContactService.ContactReq r, Authentication a) {
        return contacts.create(TenantContext.require().id(), user(a), r);
    }

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('domains.purchase')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrder(@RequestBody DomainOrderService.CreateReq r, Authentication a) {
        return orders.create(TenantContext.require().id(), user(a), r);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public List<Map<String, Object>> listOrders() { return orders.list(TenantContext.require().id()); }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> getOrder(@PathVariable UUID id) { return orders.get(TenantContext.require().id(), id); }

    /** Explicit merchant action only - never called automatically. The scheduled auto-renew path goes through {@code auto-renew} below instead. */
    @PostMapping("/orders/{id}/renew")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> renew(@PathVariable UUID id, @RequestBody(required = false) DomainOrderService.RenewReq r, Authentication a) {
        return orders.renew(TenantContext.require().id(), user(a), id, r);
    }

    /** The opt-in toggle the renewal job checks before it is ever allowed to auto-charge the wallet. Off by default. */
    @PutMapping("/orders/{id}/auto-renew")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> autoRenew(@PathVariable UUID id, @RequestBody DomainOrderService.AutoRenewReq r, Authentication a) {
        return orders.setAutoRenew(TenantContext.require().id(), user(a), id, r);
    }

    /** Ownership clarity: the legal registrant behind this order, shown plainly. */
    @GetMapping("/orders/{id}/registrant")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> registrant(@PathVariable UUID id) { return orders.registrant(TenantContext.require().id(), id); }

    /** Ownership protection: unlock the domain and issue a fresh EPP/auth code to transfer out or recover it. */
    @PostMapping("/orders/{id}/transfer-auth-code")
    @PreAuthorize("hasAuthority('domains.purchase')")
    public Map<String, Object> transferAuthCode(@PathVariable UUID id, Authentication a) {
        return orders.transferAuthCode(TenantContext.require().id(), user(a), id);
    }
}

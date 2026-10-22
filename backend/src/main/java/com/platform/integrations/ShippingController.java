package com.platform.integrations;

import com.platform.commerce.StoreContext;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Shipping carriers (filtered by country) and shipments of the shop behind the host. */
@RestController
@RequestMapping("/api/v1/store/shipping")
public class ShippingController {
    public record Patch(String label, Map<String, String> credentials) {}

    private static final String READ = "hasAuthority('carrier.read') or hasAuthority('carrier.manage')";
    private static final String WRITE = "hasAuthority('carrier.manage')";

    private final IntegrationService integrations;
    private final ShippingService shipping;

    public ShippingController(IntegrationService integrations, ShippingService shipping) {
        this.integrations = integrations;
        this.shipping = shipping;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/carriers/catalog")
    @PreAuthorize(READ)
    public List<Map<String, Object>> catalog(@RequestParam(required = false) String country) { return integrations.catalog(country, Providers.CARRIER); }

    @GetMapping("/carriers")
    @PreAuthorize(READ)
    public List<Map<String, Object>> accounts() { return integrations.accounts(StoreContext.tenantId(), Providers.CARRIER); }

    @PostMapping("/carriers")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> connect(@RequestBody IntegrationService.ConnectReq r, Authentication a) {
        Providers.Def d = Providers.get(r.provider());
        if (d == null || !Providers.CARRIER.equals(d.kind())) throw BusinessException.badRequest("PROVIDER_UNAVAILABLE", "Choose a shipping company");
        return integrations.connect(StoreContext.tenantId(), user(a), r);
    }

    @PatchMapping("/carriers/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody Patch r, Authentication a) {
        requireCarrier(id);
        return integrations.updateCredentials(StoreContext.tenantId(), user(a), id, r.label(), r.credentials());
    }

    @DeleteMapping("/carriers/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> disconnect(@PathVariable UUID id, Authentication a) {
        requireCarrier(id);
        integrations.disconnect(StoreContext.tenantId(), user(a), id);
        return Map.of("ok", true);
    }

    private void requireCarrier(UUID id) {
        if (!Providers.CARRIER.equals(integrations.row(StoreContext.tenantId(), id).get("kind"))) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found");
    }

    @GetMapping("/shipments")
    @PreAuthorize(READ)
    public Map<String, Object> shipments(@RequestParam(required = false) UUID orderId, @RequestParam(required = false) UUID channelOrderId, @RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return shipping.list(StoreContext.tenantId(), orderId, channelOrderId, q, Page.of(page, pageSize));
    }

    @PostMapping("/shipments")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> ship(@RequestBody ShippingService.ShipReq r, Authentication a) { return shipping.ship(StoreContext.tenantId(), user(a), r); }

    @PostMapping("/shipments/{id}/refresh")
    @PreAuthorize(READ)
    public Map<String, Object> refresh(@PathVariable UUID id) { return shipping.refresh(StoreContext.tenantId(), id); }

    @PostMapping("/shipments/{id}/cancel")
    @PreAuthorize(WRITE)
    public Map<String, Object> cancel(@PathVariable UUID id, Authentication a) { return shipping.cancel(StoreContext.tenantId(), user(a), id); }
}

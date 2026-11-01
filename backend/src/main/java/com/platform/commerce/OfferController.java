package com.platform.commerce;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The offers screen: every kind of automatic offer, plus coupons in the same list. */
@RestController
@RequestMapping("/api/v1/store/offers")
public class OfferController {
    private final OfferService offers;

    public OfferController(OfferService offers) { this.offers = offers; }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping
    @PreAuthorize("(hasAuthority('coupon.manage') or hasAuthority('coupon.read'))")
    public List<Map<String, Object>> list(@RequestParam(required = false) String state, @RequestParam(required = false) String type, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to) {
        return offers.list(StoreContext.tenantId(), state, type, q, from, to);
    }

    @GetMapping("/stats")
    @PreAuthorize("(hasAuthority('coupon.manage') or hasAuthority('coupon.read'))")
    public Map<String, Object> stats() { return offers.stats(StoreContext.tenantId()); }

    @GetMapping("/{id}")
    @PreAuthorize("(hasAuthority('coupon.manage') or hasAuthority('coupon.read'))")
    public Map<String, Object> get(@PathVariable UUID id) { return offers.get(StoreContext.tenantId(), id); }

    @PostMapping
    @PreAuthorize("hasAuthority('coupon.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody OfferService.OfferReq r, Authentication a) { return offers.create(StoreContext.tenantId(), user(a), r); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody OfferService.OfferReq r, Authentication a) { return offers.update(StoreContext.tenantId(), user(a), id, r); }

    public record ActiveReq(boolean active) {}

    @PutMapping("/{id}/active")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public Map<String, Object> active(@PathVariable UUID id, @RequestBody ActiveReq r, Authentication a) { return offers.setActive(StoreContext.tenantId(), user(a), id, r.active()); }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAuthority('coupon.manage')")
    public Map<String, Object> duplicate(@PathVariable UUID id, Authentication a) { return offers.duplicate(StoreContext.tenantId(), user(a), id); }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('coupon.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication a) { offers.delete(StoreContext.tenantId(), user(a), id); }
}

package com.platform.affiliate;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The shop's affiliate program: settings, marketers, commissions, payments; plus the public visit counter used by the storefront. */
@RestController
public class AffiliateController {
    public record ProgramReq(Boolean enabled, Double percent, Integer windowDays, Integer holdDays, Long minPayoutMinor, String terms, Boolean listed, Boolean autoApprove, String pitch) {}
    public record PartnerReq(String name, String phone, Double percent) {}
    public record PartnerPatch(String status, Double percent, Boolean useProgramRate) {}
    public record VisitReq(String ref) {}
    public record EvidenceReq(String evidence) {}
    public record DecisionReq(String decision) {}
    public record OfferReq(UUID partnerId, String title, Double percent, String startsAt, String endsAt) {}
    public record ActiveReq(Boolean active) {}
    public record RejectReq(String note) {}
    public record RateReq(Integer rating, String comment) {}
    public record TextReq(String body) {}
    public record DisputeReq(UUID partnerId, UUID commissionId, String reason) {}
    public record ResolveReq(String resolution) {}

    private static final String MANAGE = "hasAuthority('affiliate.manage')";
    private final AffiliateService svc;
    private final ShopAffiliate shop;

    public AffiliateController(AffiliateService svc, ShopAffiliate shop) { this.svc = svc; this.shop = shop; }

    @GetMapping("/api/v1/store/affiliate") @PreAuthorize(MANAGE)
    public Map<String, Object> overview() {
        var t = StoreContext.tenantId();
        return Map.of("program", svc.program(t), "summary", svc.summary(t), "partners", svc.partners(t));
    }

    @PutMapping("/api/v1/store/affiliate/program") @PreAuthorize(MANAGE)
    public Map<String, Object> saveProgram(@RequestBody ProgramReq r) {
        var cur = svc.program(StoreContext.tenantId());
        return svc.saveProgram(StoreContext.tenantId(),
                r.enabled() != null ? r.enabled() : Boolean.TRUE.equals(cur.get("enabled")),
                r.percent() != null ? r.percent() : ((Number) cur.get("percent")).doubleValue(),
                r.windowDays() != null ? r.windowDays() : ((Number) cur.get("windowDays")).intValue(),
                r.holdDays() != null ? r.holdDays() : ((Number) cur.get("holdDays")).intValue(),
                r.minPayoutMinor() != null ? r.minPayoutMinor() : ((Number) cur.get("minPayoutMinor")).longValue(),
                r.terms() != null ? r.terms() : String.valueOf(cur.get("terms") == null ? "" : cur.get("terms")),
                r.listed() != null ? r.listed() : Boolean.TRUE.equals(cur.get("listed")),
                r.autoApprove() != null ? r.autoApprove() : Boolean.TRUE.equals(cur.get("autoApprove")),
                r.pitch() != null ? r.pitch() : String.valueOf(cur.get("pitch") == null ? "" : cur.get("pitch")));
    }

    @PostMapping("/api/v1/store/affiliate/partners") @PreAuthorize(MANAGE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addPartner(@RequestBody PartnerReq r) { return svc.addPartner(StoreContext.tenantId(), r.name(), r.phone(), r.percent()); }

    @PatchMapping("/api/v1/store/affiliate/partners/{id}") @PreAuthorize(MANAGE)
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody PartnerPatch r) { return svc.updatePartner(StoreContext.tenantId(), id, r.status(), r.percent(), Boolean.TRUE.equals(r.useProgramRate())); }

    @PostMapping("/api/v1/store/affiliate/partners/{id}/payout") @PreAuthorize(MANAGE)
    public Map<String, Object> payout(@PathVariable UUID id, @RequestBody(required = false) EvidenceReq r) { return Map.of("paidMinor", svc.recordPayout(StoreContext.tenantId(), id, r == null ? null : r.evidence())); }

    @GetMapping("/api/v1/store/affiliate/commissions") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> commissions(@RequestParam(required = false) String status, @RequestParam(required = false) UUID partnerId, @RequestParam(defaultValue = "100") int limit) {
        return svc.commissions(StoreContext.tenantId(), status, partnerId, limit);
    }

    /** Public: the storefront reports a visit that came with ?ref=CODE; the answer is how many days to remember the code (null when it is not valid). */
    @PostMapping("/api/v1/shop/affiliate/visit")
    public Map<String, Object> visit(@RequestBody VisitReq r) {
        Integer days = svc.visit(StoreContext.tenantId(), r.ref());
        return days == null ? Map.of("valid", false) : Map.of("valid", true, "days", days);
    }

    @GetMapping("/api/v1/store/affiliate/badges") @PreAuthorize(MANAGE)
    public Map<String, Object> badges() { return shop.badges(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/affiliate/applications") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> applications(@RequestParam(required = false) String status) { return shop.applications(StoreContext.tenantId(), status); }

    @PostMapping("/api/v1/store/affiliate/partners/{id}/decision") @PreAuthorize(MANAGE)
    public Map<String, Object> decide(@PathVariable UUID id, @RequestBody DecisionReq r) { return shop.decide(StoreContext.tenantId(), id, r.decision()); }

    @GetMapping("/api/v1/store/affiliate/offers") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> offers() { return shop.offers(StoreContext.tenantId()); }

    @PostMapping("/api/v1/store/affiliate/offers") @PreAuthorize(MANAGE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addOffer(@RequestBody OfferReq r) { return shop.addOffer(StoreContext.tenantId(), r.partnerId(), r.title(), r.percent() == null ? -1 : r.percent(), r.startsAt(), r.endsAt()); }

    @PatchMapping("/api/v1/store/affiliate/offers/{id}") @PreAuthorize(MANAGE)
    public Map<String, Object> offerActive(@PathVariable UUID id, @RequestBody ActiveReq r) { shop.setOfferActive(StoreContext.tenantId(), id, Boolean.TRUE.equals(r.active())); return Map.of("ok", true); }

    @GetMapping("/api/v1/store/affiliate/payouts") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> payouts(@RequestParam(required = false) String status) { return shop.payouts(StoreContext.tenantId(), status); }

    @PostMapping("/api/v1/store/affiliate/payouts/{id}/paid") @PreAuthorize(MANAGE)
    public Map<String, Object> paid(@PathVariable UUID id, @RequestBody EvidenceReq r) { shop.markRequestPaid(StoreContext.tenantId(), id, r.evidence()); return Map.of("ok", true); }

    @PostMapping("/api/v1/store/affiliate/payouts/{id}/reject") @PreAuthorize(MANAGE)
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) RejectReq r) { shop.rejectRequest(StoreContext.tenantId(), id, r == null ? null : r.note()); return Map.of("ok", true); }

    @PostMapping("/api/v1/store/affiliate/partners/{id}/rating") @PreAuthorize(MANAGE)
    public Map<String, Object> rate(@PathVariable UUID id, @RequestBody RateReq r) { return shop.rateMarketer(StoreContext.tenantId(), id, r.rating() == null ? 0 : r.rating(), r.comment()); }

    @GetMapping("/api/v1/store/affiliate/reviews") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> reviews() { return shop.reviews(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/affiliate/inbox") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> inbox() { return shop.inbox(StoreContext.tenantId()); }

    @GetMapping("/api/v1/store/affiliate/partners/{id}/messages") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> thread(@PathVariable UUID id) { return shop.thread(StoreContext.tenantId(), id); }

    @PostMapping("/api/v1/store/affiliate/partners/{id}/messages") @PreAuthorize(MANAGE)
    public Map<String, Object> send(@PathVariable UUID id, @RequestBody TextReq r) { shop.send(StoreContext.tenantId(), id, r.body()); return Map.of("ok", true); }

    @GetMapping("/api/v1/store/affiliate/disputes") @PreAuthorize(MANAGE)
    public List<Map<String, Object>> disputes() { return shop.disputes(StoreContext.tenantId()); }

    @PostMapping("/api/v1/store/affiliate/disputes") @PreAuthorize(MANAGE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> openDispute(@RequestBody DisputeReq r) { shop.openDispute(StoreContext.tenantId(), r.partnerId(), r.commissionId(), r.reason()); return Map.of("ok", true); }

    @PostMapping("/api/v1/store/affiliate/disputes/{id}/resolve") @PreAuthorize(MANAGE)
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody ResolveReq r) { shop.resolveDispute(StoreContext.tenantId(), id, r.resolution()); return Map.of("ok", true); }
}

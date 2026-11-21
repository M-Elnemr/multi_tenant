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
    public record ProgramReq(Boolean enabled, Double percent, Integer windowDays, Integer holdDays, Long minPayoutMinor, String terms) {}
    public record PartnerReq(String name, String phone, Double percent) {}
    public record PartnerPatch(String status, Double percent, Boolean useProgramRate) {}
    public record VisitReq(String ref) {}

    private static final String MANAGE = "hasAuthority('affiliate.manage')";
    private final AffiliateService svc;

    public AffiliateController(AffiliateService svc) { this.svc = svc; }

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
                r.terms() != null ? r.terms() : String.valueOf(cur.get("terms") == null ? "" : cur.get("terms")));
    }

    @PostMapping("/api/v1/store/affiliate/partners") @PreAuthorize(MANAGE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addPartner(@RequestBody PartnerReq r) { return svc.addPartner(StoreContext.tenantId(), r.name(), r.phone(), r.percent()); }

    @PatchMapping("/api/v1/store/affiliate/partners/{id}") @PreAuthorize(MANAGE)
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody PartnerPatch r) { return svc.updatePartner(StoreContext.tenantId(), id, r.status(), r.percent(), Boolean.TRUE.equals(r.useProgramRate())); }

    @PostMapping("/api/v1/store/affiliate/partners/{id}/payout") @PreAuthorize(MANAGE)
    public Map<String, Object> payout(@PathVariable UUID id) { return Map.of("paidMinor", svc.recordPayout(StoreContext.tenantId(), id)); }

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
}

package com.platform.affiliate;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The platform admin's affiliate oversight: settings, marketers, shops, payout monitoring, disputes, fraud flags and the audit trail. */
@RestController
@RequestMapping("/api/v1/platform/affiliate")
@PreAuthorize("hasAuthority('affiliate.admin')")
public class PlatformAffiliateController {
    public record SettingsReq(Boolean registrationOpen, Boolean marketplaceOpen, Double maxPercent) {}
    public record StatusReq(String status) {}
    public record ReviewReq(String decision, String note, Boolean suspendMarketer) {}

    private final AffiliateAdmin admin;
    private final AffiliateFraud fraud;

    public PlatformAffiliateController(AffiliateAdmin admin, AffiliateFraud fraud) { this.admin = admin; this.fraud = fraud; }

    private static UUID me(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/overview") public Map<String, Object> overview() { return admin.overview(); }
    @PutMapping("/settings") public Map<String, Object> settings(@RequestBody SettingsReq r, Authentication a) { return admin.saveSettings(me(a), r.registrationOpen(), r.marketplaceOpen(), r.maxPercent()); }

    @GetMapping("/marketers")
    public Map<String, Object> marketers(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return admin.marketers(q, status, page, size); }

    @PostMapping("/marketers/{id}/status")
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusReq r, Authentication a) { admin.setAccountStatus(me(a), id, r.status()); return Map.of("ok", true); }

    @GetMapping("/shops")
    public Map<String, Object> shops(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return admin.shops(q, page, size); }

    @GetMapping("/payouts")
    public Map<String, Object> payouts(@RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return admin.payouts(status, q, page, size); }

    @GetMapping("/disputes")
    public Map<String, Object> disputes(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return admin.disputes(status, page, size); }

    @GetMapping("/flags")
    public Map<String, Object> flags(@RequestParam(required = false) String status, @RequestParam(required = false) String kind, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return fraud.flags(status, kind, q, page, size); }

    @PostMapping("/flags/{id}/review")
    public Map<String, Object> review(@PathVariable UUID id, @RequestBody ReviewReq r, Authentication a) { fraud.review(id, r.decision(), r.note(), me(a), Boolean.TRUE.equals(r.suspendMarketer())); return Map.of("ok", true); }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(required = false) String actor, @RequestParam(required = false) String action, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) { return admin.auditLog(actor, action, q, page, size); }
}

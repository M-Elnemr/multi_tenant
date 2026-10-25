package com.platform.commerce;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Wave 4: announcement A/B tests, the AI store builder and the revenue attribution report. */
@RestController
public class LeadController {
    public record StartReq(String name, String a, String b) {}
    public record ApplyReq(String winner) {}
    public record EventReq(String variant, String type, String clientKey, String orderNumber) {}
    public record BuilderReq(String description, String language) {}
    public record SocialImportReq(String platform, String url, String language) {}

    private static final String EXP = "hasAuthority('experiment.manage')";

    private final ExperimentService experiments;
    private final StoreBuilderService builder;
    private final SocialImportService socialImport;
    private final AttributionService attribution;
    private final com.platform.core.auth.LoginThrottle throttle;

    public LeadController(ExperimentService experiments, StoreBuilderService builder, SocialImportService socialImport, AttributionService attribution, com.platform.core.auth.LoginThrottle throttle) {
        this.throttle = throttle;
        this.experiments = experiments;
        this.builder = builder;
        this.socialImport = socialImport;
        this.attribution = attribution;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/api/v1/shop/experiments/active")
    public Map<String, Object> active() { return experiments.active(StoreContext.tenantId()); }

    @PostMapping("/api/v1/shop/experiments/{id}/event")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void event(@PathVariable UUID id, @RequestBody EventReq r) {
        throttle.check("exp-event|" + r.clientKey(), 40, java.time.Duration.ofHours(1));
        experiments.event(StoreContext.tenantId(), id, r.variant(), r.type(), r.clientKey(), r.orderNumber());
    }

    @GetMapping("/api/v1/store/experiments")
    @PreAuthorize(EXP)
    public List<Map<String, Object>> list() { return experiments.list(StoreContext.tenantId()); }

    @PostMapping("/api/v1/store/experiments")
    @PreAuthorize(EXP)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@RequestBody StartReq r, Authentication a) { return experiments.start(StoreContext.tenantId(), user(a), r.name(), r.a(), r.b()); }

    @PostMapping("/api/v1/store/experiments/{id}/stop")
    @PreAuthorize(EXP)
    public Map<String, Object> stop(@PathVariable UUID id, Authentication a) { return experiments.stop(StoreContext.tenantId(), user(a), id); }

    @PostMapping("/api/v1/store/experiments/{id}/apply")
    @PreAuthorize(EXP)
    public Map<String, Object> apply(@PathVariable UUID id, @RequestBody ApplyReq r, Authentication a) { return experiments.apply(StoreContext.tenantId(), user(a), id, r.winner()); }

    @PostMapping("/api/v1/store/builder")
    @PreAuthorize(EXP)
    public Map<String, Object> build(@RequestBody BuilderReq r, Authentication a) { return builder.generate(StoreContext.tenantId(), user(a), r.description(), r.language()); }

    /** Which connected Facebook page / Instagram account the shop can be started from. */
    @GetMapping("/api/v1/store/builder/social")
    @PreAuthorize(EXP)
    public List<Map<String, Object>> socialSources() { return socialImport.sources(StoreContext.tenantId()); }

    /** Starts the shop from the owner's own connected page or account: draft products from their posts, with the pictures. */
    @PostMapping("/api/v1/store/builder/social")
    @PreAuthorize(EXP)
    public Map<String, Object> buildFromSocial(@RequestBody SocialImportReq r, Authentication a) { return socialImport.importFrom(StoreContext.tenantId(), user(a), r.platform(), r.url(), r.language()); }

    @GetMapping("/api/v1/store/attribution")
    @PreAuthorize("hasAuthority('report.read')")
    public Map<String, Object> attribution(@RequestParam(required = false, defaultValue = "30") int days) { return attribution.summary(StoreContext.tenantId(), days); }
}

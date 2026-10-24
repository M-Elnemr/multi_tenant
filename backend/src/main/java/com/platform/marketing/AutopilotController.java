package com.platform.marketing;

import com.platform.commerce.StoreContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Weekly content plan, ad guardrails, the optimizer's log and copy variants of the shop behind the host. */
@RestController
@RequestMapping("/api/v1/store/marketing")
public class AutopilotController {
    public record GenerateReq(String language, Boolean withVideo) {}
    public record VariantsReq(String language) {}

    private static final String READ = "hasAuthority('marketing.read') or hasAuthority('marketing.manage')";
    private static final String WRITE = "hasAuthority('marketing.manage')";

    private final MarketingPlanService plan;
    private final AdsAutopilotService autopilot;

    public AutopilotController(MarketingPlanService plan, AdsAutopilotService autopilot) {
        this.plan = plan;
        this.autopilot = autopilot;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/plan")
    @PreAuthorize(READ)
    public Map<String, Object> list(@RequestParam(required = false) LocalDate from) { return Map.of("items", plan.list(StoreContext.tenantId(), from), "videosEnabled", plan.videosEnabled()); }

    @PostMapping("/plan/generate")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> generate(@RequestBody(required = false) GenerateReq r, Authentication a) {
        return plan.generate(StoreContext.tenantId(), user(a), r == null ? null : r.language(), r != null && Boolean.TRUE.equals(r.withVideo()));
    }

    @PatchMapping("/plan/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> edit(@PathVariable UUID id, @RequestBody MarketingPlanService.EditReq r, Authentication a) { return plan.edit(StoreContext.tenantId(), user(a), id, r); }

    @PostMapping("/plan/{id}/approve")
    @PreAuthorize(WRITE)
    public Map<String, Object> approve(@PathVariable UUID id, Authentication a) { return plan.approve(StoreContext.tenantId(), user(a), id); }

    @PostMapping("/plan/{id}/skip")
    @PreAuthorize(WRITE)
    public Map<String, Object> skip(@PathVariable UUID id) { return plan.skip(StoreContext.tenantId(), id); }

    @PostMapping("/plan/{id}/video")
    @PreAuthorize(WRITE)
    public Map<String, Object> makeVideo(@PathVariable UUID id) { return plan.makeVideo(StoreContext.tenantId(), id); }

    @GetMapping("/plan/{id}/video")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> video(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).header("Content-Disposition", "attachment; filename=\"reel.mp4\"").body(plan.video(StoreContext.tenantId(), id));
    }

    // ---- ads: guardrails, log, variants ----------------------------------------------------------------------------------------

    @GetMapping("/guardrails")
    @PreAuthorize(READ)
    public Map<String, Object> guardrails() { return autopilot.guardrails(StoreContext.tenantId()); }

    @PutMapping("/guardrails")
    @PreAuthorize(WRITE)
    public Map<String, Object> saveGuardrails(@RequestBody AdsAutopilotService.GuardrailsReq r, Authentication a) { return autopilot.saveGuardrails(StoreContext.tenantId(), user(a), r); }

    @GetMapping("/ad-actions")
    @PreAuthorize(READ)
    public List<Map<String, Object>> actions() { return autopilot.actions(StoreContext.tenantId()); }

    @PostMapping("/ad-actions/{id}/undo")
    @PreAuthorize(WRITE)
    public Map<String, Object> undo(@PathVariable long id, Authentication a) { return autopilot.undo(StoreContext.tenantId(), user(a), id); }

    @PostMapping("/ad-optimize")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> optimizeNow() { return autopilot.optimize(StoreContext.tenantId()); }

    @GetMapping("/campaigns/{id}/variants")
    @PreAuthorize(READ)
    public List<Map<String, Object>> variants(@PathVariable UUID id) { return autopilot.variants(StoreContext.tenantId(), id); }

    @PostMapping("/campaigns/{id}/variants")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> startVariants(@PathVariable UUID id, @RequestBody(required = false) VariantsReq r) { return autopilot.startVariants(StoreContext.tenantId(), id, r == null ? null : r.language()); }
}

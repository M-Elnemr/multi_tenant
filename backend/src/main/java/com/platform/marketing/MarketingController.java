package com.platform.marketing;

import com.platform.commerce.StoreContext;
import com.platform.shared.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Social posts (AI written) and ad campaigns of the shop behind the host. */
@RestController
@RequestMapping("/api/v1/store/marketing")
public class MarketingController {
    public record ScheduleReq(Instant at) {}
    public record RegenReq(String what, String tone) {}
    public record SuggestReq(UUID accountId, UUID productId, String language) {}

    private static final String READ = "hasAuthority('marketing.read') or hasAuthority('marketing.manage')";
    private static final String WRITE = "hasAuthority('marketing.manage')";

    private final SocialService social;
    private final AdsService ads;

    public MarketingController(SocialService social, AdsService ads) {
        this.social = social;
        this.ads = ads;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    /** What the screen needs first: the pages and ad accounts that are connected and whether the AI can make pictures. */
    @GetMapping("/overview")
    @PreAuthorize(READ)
    public Map<String, Object> overview() {
        UUID t = StoreContext.tenantId();
        return Map.of("channels", social.channels(t), "adAccounts", ads.adAccounts(t), "imagesAvailable", social.imagesAvailable());
    }

    // ---- posts ----------------------------------------------------------------------------------------------------

    @GetMapping("/posts")
    @PreAuthorize(READ)
    public Map<String, Object> posts(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) { return social.list(StoreContext.tenantId(), Page.of(page, pageSize)); }

    @GetMapping("/posts/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> post(@PathVariable UUID id) { return social.get(StoreContext.tenantId(), id); }

    @PostMapping("/posts/draft")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> draft(@RequestBody SocialService.DraftReq r, Authentication a) { return social.draft(StoreContext.tenantId(), user(a), r); }

    @PatchMapping("/posts/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> edit(@PathVariable UUID id, @RequestBody SocialService.Edit e, Authentication a) { return social.edit(StoreContext.tenantId(), user(a), id, e); }

    @PostMapping("/posts/{id}/regenerate")
    @PreAuthorize(WRITE)
    public Map<String, Object> regenerate(@PathVariable UUID id, @RequestBody RegenReq r, Authentication a) { return social.regenerate(StoreContext.tenantId(), user(a), id, r.what(), r.tone()); }

    @PostMapping("/posts/{id}/publish")
    @PreAuthorize(WRITE)
    public Map<String, Object> publish(@PathVariable UUID id, Authentication a) { return social.publishNow(StoreContext.tenantId(), user(a), id); }

    @PostMapping("/posts/{id}/schedule")
    @PreAuthorize(WRITE)
    public Map<String, Object> schedule(@PathVariable UUID id, @RequestBody ScheduleReq r, Authentication a) { return social.schedule(StoreContext.tenantId(), user(a), id, r.at()); }

    @PostMapping("/posts/{id}/unschedule")
    @PreAuthorize(WRITE)
    public Map<String, Object> unschedule(@PathVariable UUID id) { return social.unschedule(StoreContext.tenantId(), id); }

    @DeleteMapping("/posts/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> delete(@PathVariable UUID id) {
        social.delete(StoreContext.tenantId(), id);
        return Map.of("ok", true);
    }

    // ---- ads ------------------------------------------------------------------------------------------------------

    @GetMapping("/campaigns")
    @PreAuthorize(READ)
    public List<Map<String, Object>> campaigns() { return ads.list(StoreContext.tenantId()); }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> campaign(@PathVariable UUID id) { return ads.get(StoreContext.tenantId(), id); }

    @PostMapping("/campaigns/suggest")
    @PreAuthorize(WRITE)
    public Map<String, Object> suggest(@RequestBody SuggestReq r) { return ads.suggest(StoreContext.tenantId(), r.accountId(), r.productId(), r.language()); }

    @PostMapping("/campaigns")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody AdsService.CampaignReq r, Authentication a) { return ads.create(StoreContext.tenantId(), user(a), r); }

    @PostMapping("/campaigns/{id}/launch")
    @PreAuthorize(WRITE)
    public Map<String, Object> launch(@PathVariable UUID id, Authentication a) { return ads.setStatus(StoreContext.tenantId(), user(a), id, "ACTIVE"); }

    @PostMapping("/campaigns/{id}/pause")
    @PreAuthorize(WRITE)
    public Map<String, Object> pause(@PathVariable UUID id, Authentication a) { return ads.setStatus(StoreContext.tenantId(), user(a), id, "PAUSED"); }

    @PostMapping("/campaigns/{id}/refresh")
    @PreAuthorize(READ)
    public Map<String, Object> refresh(@PathVariable UUID id) { return ads.refresh(StoreContext.tenantId(), id); }
}

package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Automatic free follow-ups: the shop's sequence (settings), the list of scheduled / sent / cancelled / failed ones, and cancel or edit one that is waiting. */
@RestController
@RequestMapping("/api/v1/store/chat/followups")
public class FollowUpController {
    private final FollowUpService followUps;

    public FollowUpController(FollowUpService followUps) { this.followUps = followUps; }

    public record SettingsRequest(Boolean enabled, List<FollowUpService.Step> steps) {}
    public record EditRequest(String body) {}

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> settings() { return followUps.settings(StoreContext.tenantId()); }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> save(@RequestBody SettingsRequest r, Authentication a) { return followUps.save(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.enabled(), r.steps()); }

    @GetMapping
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) { return followUps.list(StoreContext.tenantId(), status); }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> stats() { return followUps.stats(StoreContext.tenantId()); }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, Authentication a) { followUps.cancel(StoreContext.tenantId(), (UUID) a.getPrincipal(), id); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(@PathVariable UUID id, @RequestBody EditRequest r) { followUps.edit(StoreContext.tenantId(), id, r.body()); }
}

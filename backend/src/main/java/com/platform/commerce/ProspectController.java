package com.platform.commerce;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** "New customers" targeting: followers and commenters who never wrote to the page. Permission {@code growth.manage}. */
@RestController
@RequestMapping("/api/v1/store/prospects")
@PreAuthorize("hasAuthority('growth.manage')")
public class ProspectController {
    public record SyncReq(String platform) {}

    public record BroadcastReq(List<String> platforms, List<String> sources, List<UUID> ids, List<UUID> excludeIds, Boolean all, String q, String message, UUID imageFileId, List<String> variants) {}

    private final ProspectService prospects;

    public ProspectController(ProspectService prospects) { this.prospects = prospects; }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String platform, @RequestParam(required = false) String source, @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return prospects.list(StoreContext.tenantId(), platform, source, q, status, limit, offset);
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody SyncReq r) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(prospects.startSync(StoreContext.tenantId(), r.platform()));
    }

    @GetMapping("/sync/status")
    public Map<String, Object> syncStatus(@RequestParam String platform) { return prospects.syncStatus(StoreContext.tenantId(), platform); }

    @PostMapping("/broadcasts/quote")
    public Map<String, Object> quote(@RequestBody BroadcastReq r) { return prospects.quote(StoreContext.tenantId(), r); }

    @PostMapping("/broadcasts")
    public Map<String, Object> broadcast(@RequestBody BroadcastReq r, Authentication a) { return prospects.broadcast(StoreContext.tenantId(), user(a), r); }
}

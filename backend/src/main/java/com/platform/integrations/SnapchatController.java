package com.platform.integrations;

import com.platform.commerce.StoreContext;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Snapchat: the login coming back and the shop's catalog screen. There is no customer-chat route because Snap has no such API. */
@RestController
@RequestMapping("/api/v1")
public class SnapchatController {
    private final SnapchatService snap;

    public SnapchatController(SnapchatService snap) { this.snap = snap; }

    @GetMapping("/chat/snapchat/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", snap.callback(code, state)).build();
    }

    @GetMapping("/store/integrations/snapchat")
    @PreAuthorize("hasAuthority('integration.read') or hasAuthority('integration.manage')")
    public Map<String, Object> status() { return snap.status(StoreContext.tenantId()); }

    @PostMapping("/store/integrations/snapchat/start")
    @PreAuthorize("hasAuthority('integration.manage')")
    public Map<String, Object> start() { return Map.of("url", snap.startUrl(StoreContext.tenantId())); }

    @PostMapping("/store/integrations/snapchat/organization")
    @PreAuthorize("hasAuthority('integration.manage')")
    public Map<String, Object> organization(@RequestBody Map<String, String> body) { return snap.chooseOrganization(StoreContext.tenantId(), body.getOrDefault("organizationId", "")); }

    @PostMapping("/store/integrations/snapchat/catalog")
    @PreAuthorize("hasAuthority('integration.manage')")
    public Map<String, Object> catalog() { return snap.publishCatalog(StoreContext.tenantId()); }

    @PostMapping("/store/integrations/snapchat/disconnect")
    @PreAuthorize("hasAuthority('integration.manage')")
    public Map<String, Object> disconnect() { snap.disconnect(StoreContext.tenantId()); return Map.of("disconnected", true); }
}

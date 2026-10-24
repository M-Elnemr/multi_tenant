package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings for the optional unofficial WhatsApp mode, and the webhook Evolution API calls inside our network. */
@RestController
@RequestMapping("/api/v1")
public class EvolutionController {
    public record EnableRequest(Boolean acceptedRisk) {}
    public record TestRequest(String number, String text) {}

    private final EvolutionService evolution;

    public EvolutionController(EvolutionService evolution) { this.evolution = evolution; }

    @GetMapping("/store/ai/evolution")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> info() { return evolution.refresh(StoreContext.tenantId()); }

    @PostMapping("/store/ai/evolution/enable")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> enable(@RequestBody EnableRequest r, Authentication a) { return evolution.enable(StoreContext.tenantId(), (UUID) a.getPrincipal(), Boolean.TRUE.equals(r.acceptedRisk())); }

    @GetMapping("/store/ai/evolution/qr")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> qr() { return evolution.qr(StoreContext.tenantId()); }

    @PostMapping("/store/ai/evolution/disable")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> disable(Authentication a) { return evolution.disable(StoreContext.tenantId(), (UUID) a.getPrincipal()); }

    @PostMapping("/store/ai/evolution/test")
    @PreAuthorize("hasAuthority('ai.manage')")
    public ResponseEntity<Void> test(@RequestBody TestRequest r) {
        evolution.test(StoreContext.tenantId(), r.number(), r.text());
        return ResponseEntity.noContent().build();
    }

    /** Evolution API reports connection changes and incoming messages here. The secret in the path proves the call came from our own service. */
    @PostMapping("/chat/evolution/webhook/{secret}")
    public ResponseEntity<Void> webhook(@PathVariable String secret, @RequestBody String raw) {
        if (!evolution.validSecret(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        evolution.handle(raw);
        return ResponseEntity.ok().build();
    }
}

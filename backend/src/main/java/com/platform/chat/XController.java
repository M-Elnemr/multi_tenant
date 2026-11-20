package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** X: the webhook (CRC check and signed events), the login callback, and the dashboard's connect screen. */
@RestController
@RequestMapping("/api/v1")
public class XController {
    private final XService x;

    public XController(XService x) { this.x = x; }

    @GetMapping("/chat/x/webhook")
    public Map<String, String> crc(@RequestParam(name = "crc_token", required = false) String crcToken) { return x.crc(crcToken); }

    @PostMapping("/chat/x/webhook")
    public ResponseEntity<Void> receive(@RequestBody String raw, @RequestHeader(value = "X-Twitter-Webhooks-Signature-OAuth2", required = false) String signature) {
        if (!x.validSignature(raw, signature)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        x.handle(raw);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/chat/x/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", x.callback(code, state)).build();
    }

    @GetMapping("/store/ai/x")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> status() { return x.status(StoreContext.tenantId()); }

    @PostMapping("/store/ai/x/start")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> start(Authentication a) { return Map.of("url", x.startUrl(StoreContext.tenantId(), (UUID) a.getPrincipal())); }

    /** The platform team registers the webhook address with X once. */
    @PostMapping("/platform/chat/x/webhook")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> register() { return Map.of("webhookId", x.registerWebhook()); }
}

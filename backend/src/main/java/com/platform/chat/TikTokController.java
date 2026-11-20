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

/** TikTok: the signed webhook, the authorization callback and the dashboard's connect screen. */
@RestController
@RequestMapping("/api/v1")
public class TikTokController {
    private final TikTokService tiktok;

    public TikTokController(TikTokService tiktok) { this.tiktok = tiktok; }

    @PostMapping("/chat/tiktok/webhook")
    public ResponseEntity<Void> receive(@RequestBody String raw, @RequestHeader(value = "Tiktok-Signature", required = false) String signature) {
        if (!tiktok.validSignature(raw, signature)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        tiktok.handle(raw);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/chat/tiktok/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", tiktok.callback(code, state)).build();
    }

    @GetMapping("/store/ai/tiktok")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> status() { return tiktok.status(StoreContext.tenantId()); }

    @PostMapping("/store/ai/tiktok/start")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> start(Authentication a) { return Map.of("url", tiktok.startUrl(StoreContext.tenantId(), (UUID) a.getPrincipal())); }

    @PostMapping("/store/ai/tiktok/recheck")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> recheck() { return tiktok.recheck(StoreContext.tenantId()); }

    /** The platform team subscribes the app to direct message events once. */
    @PostMapping("/platform/chat/tiktok/webhook")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> register() { return tiktok.registerWebhook(); }
}

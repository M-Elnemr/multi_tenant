package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Webhook for Meta (Messenger, Instagram, WhatsApp) and the channel screens of the shop dashboard. */
@RestController
@RequestMapping("/api/v1")
public class MetaController {
    private final MetaChannelService meta;

    public MetaController(MetaChannelService meta) { this.meta = meta; }

    public record ConnectRequest(String platform, String externalId, String accessToken, String pageId) {}

    /** Meta's one-time check when the webhook is saved in the app: echo the challenge if the verify token matches. */
    @GetMapping("/chat/meta/webhook")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode, @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = meta.verifyToken();
        if ("subscribe".equals(mode) && expected != null && expected.equals(token) && challenge != null) return ResponseEntity.ok(challenge);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("");
    }

    /** Meta signs every delivery with the app secret; anything unsigned or wrongly signed is refused. */
    @PostMapping("/chat/meta/webhook")
    public ResponseEntity<Void> receive(@RequestBody String raw, @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (!meta.validSignature(raw, signature)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        meta.handle(raw);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/store/ai/channels")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> channels() { return meta.channels(StoreContext.tenantId()); }

    @PostMapping("/store/ai/channels")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> connect(@RequestBody ConnectRequest r, Authentication a) {
        return meta.connect(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.platform(), r.externalId(), r.accessToken(), r.pageId());
    }

    @DeleteMapping("/store/ai/channels/{id}")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID id, Authentication a) { meta.disconnect(StoreContext.tenantId(), (UUID) a.getPrincipal(), id); }

    /** What the platform admin types into the Meta app: the webhook address and the verify token. */
    @GetMapping("/platform/chat/meta")
    @PreAuthorize("hasAuthority('platform.chat.teach')")
    public Map<String, Object> info(@org.springframework.beans.factory.annotation.Value("${app.platform.root-domain}") String root) {
        if (!meta.configured()) return Map.of("configured", false);
        return Map.of("configured", true, "webhookUrl", "https://" + root + "/api/v1/chat/meta/webhook", "verifyToken", meta.verifyToken());
    }
}

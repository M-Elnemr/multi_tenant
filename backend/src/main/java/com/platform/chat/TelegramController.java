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
import org.springframework.web.bind.annotation.RestController;

/** Telegram's webhook (secret header) and the dashboard's connect screen. */
@RestController
@RequestMapping("/api/v1")
public class TelegramController {
    private final TelegramService telegram;

    public TelegramController(TelegramService telegram) { this.telegram = telegram; }

    @PostMapping("/chat/telegram/webhook")
    public ResponseEntity<Void> receive(@RequestBody String raw, @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret) {
        if (!telegram.validSecret(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        telegram.handle(raw);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/store/ai/telegram")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> status() { return telegram.status(StoreContext.tenantId()); }

    @PostMapping("/store/ai/telegram/link")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> link(Authentication a) { return telegram.link(StoreContext.tenantId(), (UUID) a.getPrincipal()); }
}

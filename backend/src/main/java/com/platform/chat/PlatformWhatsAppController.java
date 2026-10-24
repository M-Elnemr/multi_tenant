package com.platform.chat;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The platform admin links the platform's own WhatsApp number (QR code) that sends the verification codes. */
@RestController
@RequestMapping("/api/v1/platform/whatsapp")
@PreAuthorize("hasAuthority('platform.chat.teach')")
public class PlatformWhatsAppController {
    public record TestRequest(String number) {}

    private final PlatformWhatsApp wa;

    public PlatformWhatsAppController(PlatformWhatsApp wa) { this.wa = wa; }

    @GetMapping
    public Map<String, Object> info() { return wa.refresh(); }

    @PostMapping("/enable")
    public Map<String, Object> enable() { return wa.enable(); }

    @GetMapping("/qr")
    public Map<String, Object> qr() { return wa.qr(); }

    @PostMapping("/disable")
    public Map<String, Object> disable() { return wa.disable(); }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@RequestBody TestRequest r) {
        String n = r.number() == null ? "" : r.number().replaceAll("\\D", "");
        if (n.length() < 8 || n.length() > 15) throw com.platform.shared.BusinessException.badRequest("VALIDATION_ERROR", "Type the number with its country code");
        if (!wa.send(n, "✅ رسالة تجريبية من المنصة / Test message from the platform")) throw com.platform.shared.BusinessException.conflict("EVOLUTION_SEND_FAILED", "The message could not be sent");
        return ResponseEntity.noContent().build();
    }
}

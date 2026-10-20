package com.platform.chat;

import com.platform.billing.WalletService;
import com.platform.commerce.StoreContext;
import com.platform.shared.Page;
import java.util.LinkedHashMap;
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

/** The shop owner's automatic WhatsApp confirmation: settings, readiness, prepaid credit; and the admin's side of the credit. */
@RestController
@RequestMapping("/api/v1")
public class WhatsAppAutoController {
    private final WhatsAppAutoConfirmService auto;
    private final WalletService wallet;

    public WhatsAppAutoController(WhatsAppAutoConfirmService auto, WalletService wallet) {
        this.auto = auto;
        this.wallet = wallet;
    }

    public record SettingsRequest(Boolean enabled, Integer confirmAfterHours, Boolean reminderEnabled, Integer reminderAfterHours, String brandName) {}
    public record TopupRequest(long amountMinor, String phone, String note) {}
    public record HandleRequest(boolean approve, String adminNote) {}
    public record AdjustRequest(UUID tenantId, long amountMinor, String note) {}

    @GetMapping("/store/ai/whatsapp")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> overview() {
        UUID t = StoreContext.tenantId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", auto.settings(t));
        m.put("readiness", auto.readiness(t));
        m.put("templates", auto.templates(t));
        m.put("balanceMinor", wallet.balance(t));
        m.put("priceMinor", auto.priceMinor());
        m.put("topups", wallet.topupsOf(t));
        return m;
    }

    @PutMapping("/store/ai/whatsapp")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> save(@RequestBody SettingsRequest r, Authentication a) {
        return auto.saveSettings(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.enabled(), r.confirmAfterHours(), r.reminderEnabled(), r.reminderAfterHours(), r.brandName());
    }

    @PostMapping("/store/ai/whatsapp/templates")
    @PreAuthorize("hasAuthority('ai.manage')")
    public List<Map<String, Object>> registerTemplates() { return auto.registerTemplates(StoreContext.tenantId()); }

    @PostMapping("/store/ai/whatsapp/templates/refresh")
    @PreAuthorize("hasAuthority('ai.manage')")
    public List<Map<String, Object>> refreshTemplates() { return auto.refreshTemplates(StoreContext.tenantId()); }

    @GetMapping("/store/ai/whatsapp/messages")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> messages() { return auto.messages(StoreContext.tenantId()); }

    @GetMapping("/store/ai/whatsapp/ledger")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> ledger() { return wallet.entries(StoreContext.tenantId(), 50); }

    @PostMapping("/store/ai/whatsapp/topups")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> topup(@RequestBody TopupRequest r, Authentication a) { return wallet.requestTopup(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.amountMinor(), r.phone(), r.note()); }

    // ---- platform admin ----------------------------------------------------------------------------------------

    @GetMapping("/platform/wallet/topups")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> adminTopups(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return wallet.adminTopups(status, Page.of(page, pageSize));
    }

    @PostMapping("/platform/wallet/topups/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> handle(@PathVariable UUID id, @RequestBody HandleRequest r, Authentication a) { return wallet.handleTopup((UUID) a.getPrincipal(), id, r.approve(), r.adminNote()); }

    @GetMapping("/platform/wallet/balances")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public List<Map<String, Object>> balances(@RequestParam(required = false) String q) { return wallet.balances(q); }

    @PostMapping("/platform/wallet/adjust")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> adjust(@RequestBody AdjustRequest r, Authentication a) {
        return Map.of("balanceMinor", wallet.adjust((UUID) a.getPrincipal(), r.tenantId(), r.amountMinor(), r.note()));
    }
}

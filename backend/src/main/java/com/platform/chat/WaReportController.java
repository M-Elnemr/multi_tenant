package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Settings for the daily WhatsApp report of the shop behind the host. */
@RestController
@RequestMapping("/api/v1/store/whatsapp-report")
@PreAuthorize("hasAuthority('copilot.use')")
public class WaReportController {
    public record UpdateReq(Boolean enabled, String sendAfter, String language) {}

    private final WaReportService reports;

    public WaReportController(WaReportService reports) { this.reports = reports; }

    @GetMapping
    public Map<String, Object> status() { return reports.status(StoreContext.tenantId()); }

    @PostMapping("/link-code")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> code(Authentication a) { return reports.newCode(StoreContext.tenantId(), (UUID) a.getPrincipal()); }

    @PatchMapping
    public Map<String, Object> update(@RequestBody UpdateReq r) { return reports.update(StoreContext.tenantId(), r.enabled(), r.sendAfter(), r.language()); }

    @PostMapping("/send-now")
    public Map<String, Object> sendNow() { return reports.sendReportNow(StoreContext.tenantId()); }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink() { reports.unlink(StoreContext.tenantId()); }
}

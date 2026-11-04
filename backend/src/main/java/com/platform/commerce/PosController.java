package com.platform.commerce;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The till: a terminal's own data and its sync (authenticated by the terminal's token, {@code pos.device}), and the manager's screens (terminals, cashier PINs, shifts,
 * the day, sync issues, stock counts, settings).
 */
@RestController
@RequestMapping("/api/v1/store/pos")
public class PosController {
    public record RegisterReq(UUID branchId, String name) {}
    public record DeviceReq(String name, String receiptSize) {}
    public record PinReq(String pin) {}
    public record PushReq(List<PosSyncService.Op> ops) {}
    public record SettingsReq(String receiptSize, String receiptFooter, Integer posMaxDiscountPct) {}

    private static final String DEVICE = "hasAuthority('pos.device')";
    private static final String MANAGE = "hasAuthority('pos.manage')";

    private final PosDeviceService devices;
    private final PosSyncService sync;
    private final PosReportService reports;
    private final ReturnService returns;

    public PosController(PosDeviceService devices, PosSyncService sync, PosReportService reports, ReturnService returns) {
        this.devices = devices;
        this.sync = sync;
        this.reports = reports;
        this.returns = returns;
    }

    private static UUID t() { return StoreContext.tenantId(); }
    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }
    private static PosDeviceService.Device device(Authentication a) {
        PosDeviceService.Device d = (PosDeviceService.Device) a.getPrincipal();
        if (!d.tenantId().equals(t())) throw com.platform.shared.BusinessException.unauthorized("UNAUTHORIZED", "This terminal belongs to another shop");
        return d;
    }

    // ---- the terminal (its own token) -----------------------------------------------------------------------------------

    @GetMapping("/terminal/bootstrap")
    @PreAuthorize(DEVICE)
    public Map<String, Object> bootstrap(@RequestParam(required = false) String since, Authentication a) { return sync.bootstrap(device(a), since); }

    @PostMapping("/sync/push")
    @PreAuthorize(DEVICE)
    public Map<String, Object> push(@RequestBody PushReq r, Authentication a) { return sync.push(device(a), r == null ? List.of() : r.ops()); }

    /** A sale to return, found by its number on the server (any branch, also web orders paid and delivered), when the terminal is online and does not have it. */
    @GetMapping("/terminal/invoice")
    @PreAuthorize(DEVICE)
    public Map<String, Object> invoice(@RequestParam String q, Authentication a) { device(a); return returns.findOrder(t(), q); }

    // ---- the manager ----------------------------------------------------------------------------------------------------

    @GetMapping("/devices")
    @PreAuthorize(MANAGE)
    public List<Map<String, Object>> devices() { return devices.list(t()); }

    @PostMapping("/devices")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterReq r, Authentication a) { return devices.register(t(), user(a), r == null ? null : r.branchId(), r == null ? null : r.name()); }

    @PatchMapping("/devices/{id}")
    @PreAuthorize(MANAGE)
    public Map<String, Object> updateDevice(@PathVariable UUID id, @RequestBody DeviceReq r, Authentication a) { return devices.update(t(), user(a), id, r.name(), r.receiptSize()); }

    @PostMapping("/devices/{id}/revoke")
    @PreAuthorize(MANAGE)
    public Map<String, Object> revoke(@PathVariable UUID id, Authentication a) { devices.revoke(t(), user(a), id); return Map.of("ok", true); }

    @GetMapping("/team")
    @PreAuthorize(MANAGE)
    public List<Map<String, Object>> team() { return devices.team(t()); }

    @PutMapping("/team/{userId}/pin")
    @PreAuthorize("hasAuthority('pos.manage') or (hasAuthority('pos.use') and #userId == authentication.principal)")
    public Map<String, Object> setPin(@PathVariable UUID userId, @RequestBody PinReq r, Authentication a) { devices.setPin(t(), user(a), userId, r == null ? null : r.pin()); return Map.of("ok", true); }

    @DeleteMapping("/team/{userId}/pin")
    @PreAuthorize(MANAGE)
    public Map<String, Object> clearPin(@PathVariable UUID userId, Authentication a) { devices.clearPin(t(), user(a), userId); return Map.of("ok", true); }

    @GetMapping("/shifts")
    @PreAuthorize(MANAGE)
    public List<Map<String, Object>> shifts(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return reports.shifts(t(), from, to); }

    @GetMapping("/shifts/{id}")
    @PreAuthorize(MANAGE)
    public Map<String, Object> shift(@PathVariable UUID id) { return reports.shift(t(), id); }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('pos.manage') or hasAuthority('report.read')")
    public Map<String, Object> summary(@RequestParam(required = false) LocalDate day) { return reports.summary(t(), day); }

    @GetMapping("/issues")
    @PreAuthorize(MANAGE)
    public List<Map<String, Object>> issues(@RequestParam(required = false, defaultValue = "true") boolean open) { return reports.issues(t(), open); }

    @PostMapping("/issues/{id}/resolve")
    @PreAuthorize(MANAGE)
    public Map<String, Object> resolve(@PathVariable UUID id, Authentication a) { reports.resolve(t(), user(a), id); return Map.of("ok", true); }

    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('pos.manage') or hasAuthority('inventory.adjust')")
    public List<Map<String, Object>> counts() { return reports.counts(t()); }

    @GetMapping("/counts/{id}")
    @PreAuthorize("hasAuthority('pos.manage') or hasAuthority('inventory.adjust')")
    public Map<String, Object> count(@PathVariable UUID id) { return reports.countDetail(t(), id); }

    @PostMapping("/counts/{id}/apply")
    @PreAuthorize("hasAuthority('inventory.adjust')")
    public Map<String, Object> applyCount(@PathVariable UUID id, Authentication a) { return reports.applyCount(t(), user(a), id); }

    @PostMapping("/counts/{id}/cancel")
    @PreAuthorize("hasAuthority('pos.manage') or hasAuthority('inventory.adjust')")
    public Map<String, Object> cancelCount(@PathVariable UUID id, Authentication a) { reports.cancelCount(t(), user(a), id); return Map.of("ok", true); }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('pos.manage') or hasAuthority('pos.use')")
    public Map<String, Object> settings() { return reports.settings(t()); }

    @PutMapping("/settings")
    @PreAuthorize(MANAGE)
    public Map<String, Object> saveSettings(@RequestBody SettingsReq r, Authentication a) { return reports.saveSettings(t(), user(a), r.receiptSize(), r.receiptFooter(), r.posMaxDiscountPct()); }
}

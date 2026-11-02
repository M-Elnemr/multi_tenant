package com.platform.legal;

import com.platform.core.auth.TokenResponse;
import com.platform.notifications.NotificationService;
import com.platform.shared.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The office app for the lawyer's clients (on the office's own address). Sign-in is public; everything else needs a client token of this office. */
@RestController
@RequestMapping("/api/v1/lawyer/portal")
public class LegalPortalController {
    public record LoginReq(String phone, String password) {}
    public record ChangeReq(String currentPassword, String newPassword) {}
    public record DeviceReq(String token, String platform) {}

    private final LegalPortalService portal;
    private final NotificationService notifications;

    public LegalPortalController(LegalPortalService portal, NotificationService notifications) {
        this.portal = portal;
        this.notifications = notifications;
    }

    private static UUID me(Authentication a) { return (UUID) a.getPrincipal(); }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginReq r, HttpServletRequest req) {
        return portal.login(LegalContext.tenantId(), r.phone(), r.password(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public TokenResponse changePassword(@RequestBody ChangeReq r, Authentication a, HttpServletRequest req) {
        return portal.changePassword(me(a), r.currentPassword(), r.newPassword(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @GetMapping("/home")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public Map<String, Object> home(Authentication a) { return portal.home(LegalContext.tenantId(), me(a)); }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public Map<String, Object> caseDetail(@PathVariable UUID id, Authentication a) { return portal.caseDetail(LegalContext.tenantId(), me(a), id); }

    @GetMapping("/bookings")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public List<Map<String, Object>> bookings(Authentication a) { return portal.myBookings(LegalContext.tenantId(), me(a)); }

    @PostMapping("/bookings")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> book(@RequestBody LegalPortalService.BookReq r, Authentication a) { return portal.book(LegalContext.openTenantId(), me(a), r); }

    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public Map<String, Object> cancel(@PathVariable UUID id, Authentication a) { return portal.cancel(LegalContext.tenantId(), me(a), id); }

    /** The client's phone registers its Firebase token: hearing reminders and booking answers reach it. */
    @PostMapping("/devices")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public Map<String, Object> registerDevice(@RequestBody DeviceReq r, Authentication a) {
        notifications.registerDevice(me(a), r.token(), r.platform());
        return Map.of("ok", true);
    }

    @DeleteMapping("/devices")
    @PreAuthorize("hasRole('LEGAL_CLIENT')")
    public Map<String, Object> unregisterDevice(@RequestBody DeviceReq r, Authentication a) {
        notifications.unregisterDevice(me(a), r.token());
        return Map.of("ok", true);
    }
}

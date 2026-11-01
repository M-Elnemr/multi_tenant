package com.platform.legal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** The lawyer's public website: profile, services, free times, guest booking (name + phone) and tracking by code. */
@RestController
@RequestMapping("/api/v1/lawyer/public")
public class PublicLawyerController {
    private final LegalProfileService profiles;
    private final LegalBookingService bookings;
    private final com.platform.core.auth.LoginThrottle throttle;

    public PublicLawyerController(LegalProfileService profiles, LegalBookingService bookings, com.platform.core.auth.LoginThrottle throttle) {
        this.profiles = profiles;
        this.bookings = bookings;
        this.throttle = throttle;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile() { return profiles.publicProfile(LegalContext.tenantId()); }

    @GetMapping("/availability")
    public List<Map<String, Object>> availability(@RequestParam(required = false) UUID serviceId, @RequestParam(required = false) LocalDate from, @RequestParam(defaultValue = "21") int days) {
        return bookings.availability(LegalContext.tenantId(), serviceId, from, days);
    }

    @GetMapping("/slots")
    public List<java.time.Instant> slots(@RequestParam(required = false) UUID serviceId, @RequestParam LocalDate date) { return bookings.slots(LegalContext.tenantId(), serviceId, date, true); }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> book(@RequestBody LegalBookingService.BookingReq r, jakarta.servlet.http.HttpServletRequest req) {
        throttle.check("legal-book|" + com.platform.shared.ClientInfo.ip(req), 10, java.time.Duration.ofMinutes(30));
        return bookings.request(LegalContext.openTenantId(), r);
    }

    public record TrackReq(String code, String phone) {}

    @PostMapping("/bookings/track")
    public Map<String, Object> track(@RequestBody TrackReq r, jakarta.servlet.http.HttpServletRequest req) {
        throttle.check("legal-track|" + com.platform.shared.ClientInfo.ip(req), 30, java.time.Duration.ofMinutes(10));
        return bookings.track(LegalContext.tenantId(), r.code(), r.phone());
    }

    @PostMapping("/bookings/cancel")
    public Map<String, Object> cancel(@RequestBody TrackReq r, jakarta.servlet.http.HttpServletRequest req) {
        throttle.check("legal-track|" + com.platform.shared.ClientInfo.ip(req), 30, java.time.Duration.ofMinutes(10));
        return bookings.cancelByGuest(LegalContext.tenantId(), r.code(), r.phone());
    }
}

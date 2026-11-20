package com.platform.booking;

import com.platform.commerce.StoreContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The shop website's booking page: the same free times and rules as the assistant, for a visitor with no account (the shop is the host they are on). */
@RestController
@RequestMapping("/api/v1/shop/booking")
public class ShopBookingController {
    private final BookingService bookings;

    public ShopBookingController(BookingService bookings) { this.bookings = bookings; }

    public record BookReq(UUID serviceId, String date, String time, String name, String phone, String note, List<UUID> addonIds, Map<String, String> answers, String locationMode, String address) {}
    public record CancelReq(String bookingNumber, String phone) {}

    @GetMapping("/config")
    public Map<String, Object> config() {
        UUID t = StoreContext.tenantId();
        var s = bookings.settings(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("services", bookings.services(t, true));
        m.put("hours", bookings.hours(t));
        m.put("specialDays", bookings.specialDays(t));
        m.put("categories", bookings.categories(t));
        m.put("cancelDeadlineHours", s.get("cancelDeadlineHours"));
        m.put("autoConfirm", s.get("autoConfirm"));
        m.put("maxDaysAhead", s.get("maxDaysAhead"));
        m.put("timezone", s.get("timezone"));
        return m;
    }

    @GetMapping("/slots")
    public List<String> slots(@RequestParam UUID serviceId, @RequestParam String date, @RequestParam(required = false) List<UUID> addonIds) { return bookings.slots(StoreContext.tenantId(), serviceId, LocalDate.parse(date), true, addonIds == null ? List.of() : addonIds); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> book(@RequestBody BookReq r) {
        var b = bookings.book(StoreContext.tenantId(), null, new BookingService.BookReq(r.serviceId(), r.date(), r.time(), r.name(), r.phone(), r.note(), "WEBSITE", null, r.addonIds(), r.answers(), r.locationMode(), r.address()));
        return Map.of("bookingNumber", b.get("bookingNumber"), "status", b.get("status"), "serviceName", b.get("serviceName"), "date", b.get("date"), "time", b.get("time"), "priceMinor", b.get("priceMinor"), "currency", b.get("currency"));
    }

    @GetMapping("/lookup")
    public Map<String, Object> lookup(@RequestParam String number, @RequestParam String phone) {
        var b = bookings.byNumberAndPhone(StoreContext.tenantId(), number, phone);
        return Map.of("bookingNumber", b.get("bookingNumber"), "status", b.get("status"), "serviceName", b.get("serviceName"), "date", b.get("date"), "time", b.get("time"), "priceMinor", b.get("priceMinor"), "currency", b.get("currency"));
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody CancelReq r) {
        var found = bookings.byNumberAndPhone(StoreContext.tenantId(), r.bookingNumber(), r.phone());
        var b = bookings.cancel(StoreContext.tenantId(), (UUID) found.get("id"), true, null);
        return Map.of("bookingNumber", b.get("bookingNumber"), "status", b.get("status"));
    }
}

package com.platform.booking;

import com.platform.commerce.StoreContext;
import com.platform.shared.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The shop dashboard's services and appointments (permissions booking.read / booking.manage). */
@RestController
@RequestMapping("/api/v1/store")
public class BookingController {
    private static final String READ = "hasAuthority('booking.read') or hasAuthority('booking.manage')";
    private static final String WRITE = "hasAuthority('booking.manage')";
    private final BookingService bookings;

    public BookingController(BookingService bookings) { this.bookings = bookings; }

    // ---- services ----
    @GetMapping("/services")
    @PreAuthorize(READ)
    public List<Map<String, Object>> services() { return bookings.services(StoreContext.tenantId(), false); }

    @PostMapping("/services")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createService(@RequestBody BookingService.ServiceReq r) { return bookings.createService(StoreContext.tenantId(), r); }

    @PatchMapping("/services/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> updateService(@PathVariable UUID id, @RequestBody BookingService.ServiceReq r) { return bookings.updateService(StoreContext.tenantId(), id, r); }

    @DeleteMapping("/services/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteService(@PathVariable UUID id) { bookings.deleteService(StoreContext.tenantId(), id); }

    // ---- hours, days off, rules ----
    public record HoursPut(List<BookingService.Range> ranges) {}
    public record ClosureReq(String date, String to, String reason) {}
    public record SpecialDayPut(String date, List<BookingService.Range> ranges) {}

    @GetMapping("/service-categories")
    @PreAuthorize(READ)
    public List<Map<String, Object>> categories() { return bookings.categories(StoreContext.tenantId()); }

    @PostMapping("/service-categories")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@RequestBody BookingService.CategoryReq r) { return bookings.saveCategory(StoreContext.tenantId(), null, r); }

    @PatchMapping("/service-categories/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody BookingService.CategoryReq r) { return bookings.saveCategory(StoreContext.tenantId(), id, r); }

    @DeleteMapping("/service-categories/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id) { bookings.deleteCategory(StoreContext.tenantId(), id); }

    @PostMapping("/services/{id}/addons")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAddon(@PathVariable UUID id, @RequestBody BookingService.AddonReq r) { return bookings.saveAddon(StoreContext.tenantId(), id, null, r); }

    @PatchMapping("/services/{id}/addons/{addonId}")
    @PreAuthorize(WRITE)
    public Map<String, Object> updateAddon(@PathVariable UUID id, @PathVariable UUID addonId, @RequestBody BookingService.AddonReq r) { return bookings.saveAddon(StoreContext.tenantId(), id, addonId, r); }

    @DeleteMapping("/services/{id}/addons/{addonId}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddon(@PathVariable UUID id, @PathVariable UUID addonId) { bookings.deleteAddon(StoreContext.tenantId(), id, addonId); }

    public record DayOffReq(String date, String reason) {}

    @GetMapping("/staff")
    @PreAuthorize(READ)
    public List<Map<String, Object>> staff() { return bookings.staff(StoreContext.tenantId()); }

    @PostMapping("/staff")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createStaff(@RequestBody BookingService.StaffReq r) { return bookings.saveStaff(StoreContext.tenantId(), null, r); }

    @PatchMapping("/staff/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> updateStaff(@PathVariable UUID id, @RequestBody BookingService.StaffReq r) { return bookings.saveStaff(StoreContext.tenantId(), id, r); }

    @DeleteMapping("/staff/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStaff(@PathVariable UUID id) { bookings.deleteStaff(StoreContext.tenantId(), id); }

    @PostMapping("/staff/{id}/days-off")
    @PreAuthorize(WRITE)
    public Map<String, Object> staffDayOff(@PathVariable UUID id, @RequestBody DayOffReq r) { return bookings.staffDayOff(StoreContext.tenantId(), id, r.date(), r.reason(), false); }

    @DeleteMapping("/staff/{id}/days-off/{date}")
    @PreAuthorize(WRITE)
    public Map<String, Object> removeStaffDayOff(@PathVariable UUID id, @PathVariable String date) { return bookings.staffDayOff(StoreContext.tenantId(), id, date, null, true); }

    @GetMapping("/booking/special-days")
    @PreAuthorize(READ)
    public List<Map<String, Object>> specialDays() { return bookings.specialDays(StoreContext.tenantId()); }

    @PutMapping("/booking/special-days")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> saveSpecialDay(@RequestBody SpecialDayPut r) { return bookings.saveSpecialDay(StoreContext.tenantId(), r.date(), r.ranges()); }

    @GetMapping("/booking/hours")
    @PreAuthorize(READ)
    public List<Map<String, Object>> hours() { return bookings.hours(StoreContext.tenantId()); }

    @PutMapping("/booking/hours")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> saveHours(@RequestBody HoursPut r) { return bookings.saveHours(StoreContext.tenantId(), r.ranges()); }

    @GetMapping("/booking/closures")
    @PreAuthorize(READ)
    public List<Map<String, Object>> closures() { return bookings.closures(StoreContext.tenantId()); }

    @PostMapping("/booking/closures")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> addClosure(@RequestBody ClosureReq r) { return r.to() == null || r.to().isBlank() ? bookings.addClosure(StoreContext.tenantId(), r.date(), r.reason()) : bookings.addClosureRange(StoreContext.tenantId(), r.date(), r.to(), r.reason()); }

    @DeleteMapping("/booking/closures/{date}")
    @PreAuthorize(WRITE)
    public List<Map<String, Object>> removeClosure(@PathVariable String date) { return bookings.removeClosure(StoreContext.tenantId(), date); }

    @GetMapping("/booking/settings")
    @PreAuthorize(READ)
    public Map<String, Object> settings() { return bookings.settings(StoreContext.tenantId()); }

    @PutMapping("/booking/settings")
    @PreAuthorize(WRITE)
    public Map<String, Object> saveSettings(@RequestBody BookingService.SettingsReq r) { return bookings.saveSettings(StoreContext.tenantId(), r); }

    /** Free times for the dashboard's own booking form (the shop may book closer in than a customer). */
    @GetMapping("/booking/slots")
    @PreAuthorize(READ)
    public List<String> slots(@RequestParam UUID serviceId, @RequestParam String date, @RequestParam(required = false) List<UUID> addonIds, @RequestParam(required = false) UUID staffId) { return bookings.slots(StoreContext.tenantId(), serviceId, LocalDate.parse(date), false, addonIds == null ? List.of() : addonIds, staffId); }

    // ---- appointments ----
    public record DeclineReq(String reason) {}
    public record RescheduleReq(String date, String time) {}

    @GetMapping("/bookings")
    @PreAuthorize(READ)
    public Map<String, Object> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return bookings.list(StoreContext.tenantId(), from, to, status, q, Page.of(page, pageSize));
    }

    @GetMapping("/bookings/analytics")
    @PreAuthorize(READ)
    public Map<String, Object> analytics(@RequestParam String from, @RequestParam String to) { return bookings.analytics(StoreContext.tenantId(), from, to); }

    @GetMapping("/bookings/calendar")
    @PreAuthorize(READ)
    public List<Map<String, Object>> calendar(@RequestParam String from, @RequestParam String to) { return bookings.calendar(StoreContext.tenantId(), from, to); }

    @GetMapping("/bookings/summary")
    @PreAuthorize(READ)
    public Map<String, Object> summary() { return bookings.summary(StoreContext.tenantId()); }

    @PostMapping("/bookings")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody BookingService.BookReq r, Authentication a) {
        return bookings.book(StoreContext.tenantId(), (UUID) a.getPrincipal(), new BookingService.BookReq(r.serviceId(), r.date(), r.time(), r.name(), r.phone(), r.note(), "DASHBOARD", null, r.addonIds(), r.answers(), r.locationMode(), r.address(), r.staffId()));
    }

    @PostMapping("/bookings/{id}/confirm")
    @PreAuthorize(WRITE)
    public Map<String, Object> confirm(@PathVariable UUID id) { return bookings.confirm(StoreContext.tenantId(), id); }

    @PostMapping("/bookings/{id}/decline")
    @PreAuthorize(WRITE)
    public Map<String, Object> decline(@PathVariable UUID id, @RequestBody(required = false) DeclineReq r) { return bookings.decline(StoreContext.tenantId(), id, r == null ? null : r.reason()); }

    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize(WRITE)
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) DeclineReq r) { return bookings.cancel(StoreContext.tenantId(), id, false, r == null ? null : r.reason()); }

    @PostMapping("/bookings/{id}/complete")
    @PreAuthorize(WRITE)
    public Map<String, Object> complete(@PathVariable UUID id) { return bookings.finish(StoreContext.tenantId(), id, false); }

    @PostMapping("/bookings/{id}/no-show")
    @PreAuthorize(WRITE)
    public Map<String, Object> noShow(@PathVariable UUID id) { return bookings.finish(StoreContext.tenantId(), id, true); }

    @PostMapping("/bookings/{id}/reschedule")
    @PreAuthorize(WRITE)
    public Map<String, Object> reschedule(@PathVariable UUID id, @RequestBody RescheduleReq r) { return bookings.reschedule(StoreContext.tenantId(), id, r.date(), r.time(), false); }
}

package com.platform.legal;

import com.platform.shared.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The lawyer's dashboard API: profile/website, services, hours, bookings, schedule, clients, cases, finance and search. */
@RestController
@RequestMapping("/api/v1/lawyer")
public class LawyerController {
    private final LegalProfileService profiles;
    private final LegalCaseService cases;
    private final LegalBookingService bookings;

    public LawyerController(LegalProfileService profiles, LegalCaseService cases, LegalBookingService bookings) {
        this.profiles = profiles;
        this.cases = cases;
        this.bookings = bookings;
    }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }
    private static UUID t() { return LegalContext.tenantId(); }

    // ---- home, search ------------------------------------------------------------------------------------------
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('legal.case.read') or hasAuthority('legal.schedule.read') or hasAuthority('legal.booking.read')")
    public Map<String, Object> overview() { return cases.overview(t()); }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('legal.case.read') or hasAuthority('legal.client.read')")
    public Map<String, Object> search(@RequestParam String q) { return cases.search(t(), q); }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of("caseTypes", LegalCaseService.CASE_TYPES.stream().sorted().toList(), "practiceAreas", LegalProfileService.areaCatalog());
    }

    // ---- profile & website ---------------------------------------------------------------------------------------
    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public Map<String, Object> profile() { return profiles.profile(t()); }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> body, Authentication a) { return profiles.update(t(), user(a), body); }

    @GetMapping("/services")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage') or hasAuthority('legal.booking.read')")
    public List<Map<String, Object>> services() { return profiles.services(t(), false); }

    @PostMapping("/services")
    @PreAuthorize("hasAuthority('settings.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createService(@RequestBody LegalProfileService.ServiceReq r, Authentication a) { return profiles.createService(t(), user(a), r); }

    @PutMapping("/services/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateService(@PathVariable UUID id, @RequestBody LegalProfileService.ServiceReq r, Authentication a) { return profiles.updateService(t(), user(a), id, r); }

    @DeleteMapping("/services/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteService(@PathVariable UUID id, Authentication a) { profiles.deleteService(t(), user(a), id); }

    @GetMapping("/hours")
    @PreAuthorize("hasAuthority('legal.schedule.read') or hasAuthority('settings.read')")
    public Map<String, Object> hours() { return Map.of("hours", profiles.hours(t()), "timeOff", profiles.timeOff(t())); }

    public record HoursPut(List<LegalProfileService.HourReq> hours) {}

    @PutMapping("/hours")
    @PreAuthorize("hasAuthority('legal.schedule.manage') or hasAuthority('settings.manage')")
    public List<Map<String, Object>> setHours(@RequestBody HoursPut r, Authentication a) { return profiles.setHours(t(), user(a), r.hours()); }

    @PostMapping("/time-off")
    @PreAuthorize("hasAuthority('legal.schedule.manage') or hasAuthority('settings.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addTimeOff(@RequestBody LegalProfileService.TimeOffReq r, Authentication a) { return profiles.addTimeOff(t(), user(a), r); }

    @DeleteMapping("/time-off/{id}")
    @PreAuthorize("hasAuthority('legal.schedule.manage') or hasAuthority('settings.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeOff(@PathVariable UUID id) { profiles.deleteTimeOff(t(), id); }

    // ---- bookings ------------------------------------------------------------------------------------------------
    @GetMapping("/bookings")
    @PreAuthorize("hasAuthority('legal.booking.read')")
    public Map<String, Object> bookings(@RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "false") boolean upcoming,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return bookings.list(t(), Page.of(page, pageSize), status, q, from, to, upcoming);
    }

    public record StaffBooking(UUID serviceId, java.time.Instant startsAt, String name, String phone, String topic, UUID caseId) {}

    @PostMapping("/bookings")
    @PreAuthorize("hasAuthority('legal.booking.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBooking(@RequestBody StaffBooking r, Authentication a) {
        return bookings.createByStaff(t(), user(a), new LegalBookingService.BookingReq(r.serviceId(), r.startsAt(), r.name(), r.phone(), r.topic()), r.caseId());
    }

    @PutMapping("/bookings/{id}")
    @PreAuthorize("hasAuthority('legal.booking.manage')")
    public Map<String, Object> decide(@PathVariable UUID id, @RequestBody LegalBookingService.DecisionReq r, Authentication a) { return bookings.decide(t(), user(a), id, r); }

    @GetMapping("/slots")
    @PreAuthorize("hasAuthority('legal.booking.read') or hasAuthority('legal.schedule.read')")
    public List<java.time.Instant> slots(@RequestParam(required = false) UUID serviceId, @RequestParam LocalDate date) { return bookings.slots(t(), serviceId, date, false); }

    // ---- schedule ------------------------------------------------------------------------------------------------
    @GetMapping("/schedule")
    @PreAuthorize("hasAuthority('legal.schedule.read')")
    public List<Map<String, Object>> schedule(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return bookings.schedule(t(), from, to); }

    @PostMapping("/schedule/notes")
    @PreAuthorize("hasAuthority('legal.schedule.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addNote(@RequestBody LegalBookingService.NoteReq r, Authentication a) { return bookings.addNote(t(), user(a), r); }

    @PutMapping("/schedule/notes/{id}")
    @PreAuthorize("hasAuthority('legal.schedule.manage')")
    public Map<String, Object> updateNote(@PathVariable UUID id, @RequestBody LegalBookingService.NoteReq r, Authentication a) { bookings.updateNote(t(), user(a), id, r); return Map.of("ok", true); }

    @DeleteMapping("/schedule/notes/{id}")
    @PreAuthorize("hasAuthority('legal.schedule.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID id) { bookings.deleteNote(t(), id); }

    @PostMapping("/schedule/steps")
    @PreAuthorize("hasAuthority('legal.schedule.manage') and hasAuthority('legal.case.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addStepFromSchedule(@RequestBody LegalBookingService.StepFromScheduleReq r, Authentication a) { return bookings.addStepFromSchedule(t(), user(a), r); }

    @PostMapping("/schedule/move")
    @PreAuthorize("hasAuthority('legal.schedule.manage')")
    public Map<String, Object> move(@RequestBody LegalBookingService.MoveReq r, Authentication a) { bookings.move(t(), user(a), r); return Map.of("ok", true); }

    // ---- clients -------------------------------------------------------------------------------------------------
    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('legal.client.read')")
    public Map<String, Object> clients(@RequestParam(required = false) String q, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                       @RequestParam(required = false) String tag, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return cases.clients(t(), Page.of(page, pageSize), q, from, to, tag);
    }

    @GetMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('legal.client.read')")
    public Map<String, Object> client(@PathVariable UUID id) { return cases.client(t(), id); }

    @GetMapping("/clients/{id}/statement")
    @PreAuthorize("hasAuthority('legal.finance.read')")
    public Map<String, Object> statement(@PathVariable UUID id) { return cases.statement(t(), id); }

    @PostMapping("/clients")
    @PreAuthorize("hasAuthority('legal.client.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createClient(@RequestBody LegalCaseService.ClientReq r, Authentication a) { return cases.createClient(t(), user(a), r); }

    @PutMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('legal.client.manage')")
    public Map<String, Object> updateClient(@PathVariable UUID id, @RequestBody LegalCaseService.ClientReq r, Authentication a) { return cases.updateClient(t(), user(a), id, r); }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('legal.client.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClient(@PathVariable UUID id, Authentication a) { cases.deleteClient(t(), user(a), id); }

    // ---- cases ---------------------------------------------------------------------------------------------------
    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('legal.case.read')")
    public Map<String, Object> cases(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String outcome,
                                     @RequestParam(required = false) String type, @RequestParam(required = false) UUID clientId, @RequestParam(required = false) String court,
                                     @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String sort,
                                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return cases.cases(t(), Page.of(page, pageSize), new LegalCaseService.CaseFilter(q, status, outcome, type, clientId, court, from, to, sort));
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAuthority('legal.case.read')")
    public Map<String, Object> caseDetail(@PathVariable UUID id) { return cases.caseDetail(t(), id); }

    @PostMapping("/cases")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCase(@RequestBody LegalCaseService.CaseReq r, Authentication a) { return cases.createCase(t(), user(a), r); }

    @PutMapping("/cases/{id}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> updateCase(@PathVariable UUID id, @RequestBody LegalCaseService.CaseReq r, Authentication a) { return cases.updateCase(t(), user(a), id, r); }

    @PutMapping("/cases/{id}/status")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> caseStatus(@PathVariable UUID id, @RequestBody LegalCaseService.StatusReq r, Authentication a) { return cases.setStatus(t(), user(a), id, r); }

    @DeleteMapping("/cases/{id}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCase(@PathVariable UUID id, Authentication a) { cases.deleteCase(t(), user(a), id); }

    @PostMapping("/cases/{id}/actions")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> addAction(@PathVariable UUID id, @RequestBody LegalCaseService.ActionReq r, Authentication a) { return cases.addAction(t(), user(a), id, r); }

    @PutMapping("/cases/{id}/actions/{actionId}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> updateAction(@PathVariable UUID id, @PathVariable UUID actionId, @RequestBody LegalCaseService.ActionReq r, Authentication a) { return cases.updateAction(t(), user(a), id, actionId, r); }

    @DeleteMapping("/cases/{id}/actions/{actionId}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> deleteAction(@PathVariable UUID id, @PathVariable UUID actionId) { return cases.deleteAction(t(), id, actionId); }

    @PostMapping("/cases/{id}/steps")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> addStep(@PathVariable UUID id, @RequestBody LegalCaseService.StepReq r, Authentication a) { return cases.addStep(t(), user(a), id, r); }

    @PutMapping("/cases/{id}/steps/{stepId}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> updateStep(@PathVariable UUID id, @PathVariable UUID stepId, @RequestBody LegalCaseService.StepReq r, Authentication a) { return cases.updateStep(t(), user(a), id, stepId, r); }

    @DeleteMapping("/cases/{id}/steps/{stepId}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> deleteStep(@PathVariable UUID id, @PathVariable UUID stepId) { return cases.deleteStep(t(), id, stepId); }

    @PostMapping("/cases/{id}/payments")
    @PreAuthorize("hasAuthority('legal.finance.manage')")
    public Map<String, Object> addPayment(@PathVariable UUID id, @RequestBody LegalCaseService.PaymentReq r, Authentication a) { return cases.addPayment(t(), user(a), id, r); }

    @DeleteMapping("/cases/{id}/payments/{paymentId}")
    @PreAuthorize("hasAuthority('legal.finance.manage')")
    public Map<String, Object> deletePayment(@PathVariable UUID id, @PathVariable UUID paymentId, Authentication a) { return cases.deletePayment(t(), user(a), id, paymentId); }

    @PostMapping("/cases/{id}/documents")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> addDocument(@PathVariable UUID id, @RequestBody LegalCaseService.DocumentReq r, Authentication a) { return cases.addDocument(t(), user(a), id, r); }

    @DeleteMapping("/cases/{id}/documents/{docId}")
    @PreAuthorize("hasAuthority('legal.case.manage')")
    public Map<String, Object> deleteDocument(@PathVariable UUID id, @PathVariable UUID docId) { return cases.deleteDocument(t(), id, docId); }

    // ---- finance -------------------------------------------------------------------------------------------------
    @GetMapping("/finance")
    @PreAuthorize("hasAuthority('legal.finance.read')")
    public Map<String, Object> finance(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return cases.finance(t(), from, to); }
}

package com.platform.medical;

import com.platform.shared.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Doctor / reception / clinic-admin API. Permission-gated, scoped to the clinic resolved from the Host. */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicController {
    private final ClinicSettingsService settings;
    private final PatientService patients;
    private final AppointmentService appointments;
    private final ClinicalService clinical;
    private final SlotService slots;
    private final PatientExportService exports;
    private final com.platform.shared.PatientPortalPolicy portalPolicy;
    private final PrescriptionPdfService pdfs;

    public ClinicController(ClinicSettingsService settings, PatientService patients, AppointmentService appointments, ClinicalService clinical, SlotService slots,
                            PatientExportService exports, PrescriptionPdfService pdfs, com.platform.shared.PatientPortalPolicy portalPolicy) {
        this.portalPolicy = portalPolicy;
        this.exports = exports;
        this.pdfs = pdfs;
        this.settings = settings;
        this.patients = patients;
        this.appointments = appointments;
        this.clinical = clinical;
        this.slots = slots;
    }

    public record BranchReq(String name, String code, String address, String city, String phone) {}
    public record DoctorProfileReq(Map<String, Object> fields, List<String> specialties) {}
    public record ServiceReq(String name, String description, int durationMinutes, Long priceMinor, String visitType) {}
    public record ActiveReq(boolean active) {}
    public record StatusReq(String reason) {}
    public record UpdateNoteReq(String content, Boolean patientVisible) {}
    public record ReviewReq(boolean shareWithPatient) {}
    public record SetPasswordReq(String password) {}
    public record WalkInReq(UUID patientId, UUID doctorId, UUID branchId, UUID serviceId, String visitType) {}
    public record VisitTypeReq(String visitType) {}

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    // ---- settings -------------------------------------------------------------------------------------------------

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage') or hasAuthority('appointment.manage')")
    public Map<String, Object> profile() { return settings.profile(ClinicContext.tenantId()); }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> r, Authentication a) { return settings.updateProfile(ClinicContext.tenantId(), user(a), r); }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('appointment.manage') or hasAuthority('settings.manage')")
    public List<Map<String, Object>> branches() { return settings.branches(ClinicContext.tenantId(), false); }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('settings.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBranch(@RequestBody BranchReq r, Authentication a) { return settings.createBranch(ClinicContext.tenantId(), user(a), r.name(), r.code(), r.address(), r.city(), r.phone()); }

    @GetMapping("/doctors")
    @PreAuthorize("hasAuthority('appointment.manage') or hasAuthority('settings.manage')")
    public List<Map<String, Object>> doctors() { return settings.doctors(ClinicContext.tenantId()); }

    @GetMapping("/doctors/me")
    @PreAuthorize("hasAuthority('schedule.manage')")
    public Map<String, Object> me(Authentication a) { return settings.myDoctor(ClinicContext.tenantId(), user(a)); }

    @PatchMapping("/doctors/me")
    @PreAuthorize("hasAuthority('schedule.manage')")
    public Map<String, Object> updateMe(@RequestBody DoctorProfileReq r, Authentication a) { return settings.updateMyDoctorProfile(ClinicContext.tenantId(), user(a), r.fields() == null ? Map.of() : r.fields(), r.specialties()); }

    @GetMapping("/specialties")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> specialties() { return settings.specialties(); }

    @GetMapping("/services")
    @PreAuthorize("hasAuthority('appointment.manage') or hasAuthority('schedule.manage')")
    public List<Map<String, Object>> services() { return settings.services(ClinicContext.tenantId(), false); }

    @PostMapping("/services")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createService(@RequestBody ServiceReq r, Authentication a) { return settings.createService(ClinicContext.tenantId(), user(a), r.name(), r.description(), r.durationMinutes(), r.priceMinor(), r.visitType()); }

    @PutMapping("/services/{id}/active")
    @PreAuthorize("hasAuthority('schedule.manage')")
    public Map<String, Object> serviceActive(@PathVariable UUID id, @RequestBody ActiveReq r) {
        settings.setServiceActive(ClinicContext.tenantId(), id, r.active());
        return Map.of("ok", true);
    }

    @GetMapping("/schedules")
    @PreAuthorize("hasAuthority('appointment.manage') or hasAuthority('schedule.manage')")
    public List<Map<String, Object>> schedules(@RequestParam(required = false) UUID doctorId) { return settings.schedules(ClinicContext.tenantId(), doctorId); }

    @PostMapping("/schedules")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSchedule(@RequestBody ClinicSettingsService.ScheduleReq r, Authentication a) { return settings.createSchedule(ClinicContext.tenantId(), user(a), r); }

    @DeleteMapping("/schedules/{id}")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(@PathVariable UUID id) { settings.deleteSchedule(ClinicContext.tenantId(), id); }

    @PostMapping("/schedule-exceptions")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createException(@RequestBody ClinicSettingsService.ExceptionReq r, Authentication a) {
        settings.createException(ClinicContext.tenantId(), user(a), r);
        return Map.of("ok", true);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('appointment.manage')")
    public Map<String, Object> dashboard() { return clinical.dashboard(ClinicContext.tenantId()); }

    // ---- patients ---------------------------------------------------------------------------------------------------

    @GetMapping("/patients")
    @PreAuthorize("hasAuthority('patient.read')")
    public Map<String, Object> patients(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return patients.search(ClinicContext.tenantId(), q, Page.of(page, pageSize));
    }

    @PostMapping("/patients")
    @PreAuthorize("hasAuthority('patient.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPatient(@RequestBody PatientService.PatientReq r, Authentication a) { return patients.create(ClinicContext.tenantId(), user(a), r); }

    @GetMapping("/patients/{id}")
    @PreAuthorize("hasAuthority('patient.read')")
    public Map<String, Object> patient(@PathVariable UUID id, Authentication a) { return patients.detail(ClinicContext.tenantId(), user(a), id); }

    @PatchMapping("/patients/{id}")
    @PreAuthorize("hasAuthority('patient.update')")
    public Map<String, Object> updatePatient(@PathVariable UUID id, @RequestBody PatientService.PatientReq r, Authentication a) { return patients.update(ClinicContext.tenantId(), user(a), id, r); }

    @PostMapping("/patients/{id}/access-pin")
    @PreAuthorize("hasAuthority('patient.update')")
    public Map<String, Object> pin(@PathVariable UUID id, Authentication a) { portalPolicy.requireEnabled(); return patients.reissuePin(ClinicContext.tenantId(), user(a), id); }

    @PostMapping("/patients/{id}/set-password")
    @PreAuthorize("hasAuthority('patient.update')")
    public Map<String, Object> setPassword(@PathVariable UUID id, @RequestBody SetPasswordReq r, Authentication a) {
        portalPolicy.requireEnabled();
        patients.setPassword(ClinicContext.tenantId(), user(a), id, r.password());
        return Map.of("ok", true);
    }

    @GetMapping("/patients/{id}/timeline")
    @PreAuthorize("hasAuthority('patient.read') and (hasAuthority('medical_note.create') or hasAuthority('lab_order.create') or hasAuthority('prescription.create'))")
    public Map<String, Object> timeline(@PathVariable UUID id, Authentication a) { return clinical.staffTimeline(ClinicContext.tenantId(), user(a), id); }

    /** Full chart as a ZIP (record JSON, prescription PDFs, uploaded files). Audited and rate limited. */
    @GetMapping("/patients/{id}/export")
    @PreAuthorize("hasAuthority('patient.export')")
    public org.springframework.http.ResponseEntity<byte[]> export(@PathVariable UUID id, Authentication a) {
        byte[] zip = exports.export(ClinicContext.tenantId(), user(a), id, false);
        return attachment(zip, "application/zip", "patient-record.zip");
    }

    @GetMapping("/prescriptions/{id}/pdf")
    @PreAuthorize("hasAuthority('prescription.create') or (hasAuthority('patient.read') and hasAuthority('medical_note.create'))")
    public org.springframework.http.ResponseEntity<byte[]> prescriptionPdf(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean inline, Authentication a) {
        return attachment(pdfs.render(ClinicContext.tenantId(), id, false), "application/pdf", "prescription.pdf", inline);
    }

    static org.springframework.http.ResponseEntity<byte[]> attachment(byte[] data, String type, String filename) { return attachment(data, type, filename, false); }

    /** inline = shown in the browser (used to print straight from the page); otherwise downloaded. */
    static org.springframework.http.ResponseEntity<byte[]> attachment(byte[] data, String type, String filename, boolean inline) {
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(type))
                .header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + filename + "\"").header("X-Content-Type-Options", "nosniff")
                .cacheControl(org.springframework.http.CacheControl.noStore().cachePrivate()).body(data);
    }

    @PostMapping("/patients/{id}/documents")
    @PreAuthorize("hasAuthority('patient.update')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addDocument(@PathVariable UUID id, @RequestBody ClinicalService.DocumentReq r, Authentication a) { return clinical.addDocument(ClinicContext.tenantId(), user(a), id, r); }

    // ---- appointments -----------------------------------------------------------------------------------------------

    @GetMapping("/appointments")
    @PreAuthorize("hasAuthority('appointment.manage')")
    public Map<String, Object> appointments(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID doctorId,
                                            @RequestParam(required = false) String status, @RequestParam(required = false) UUID patientId,
                                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return appointments.list(ClinicContext.tenantId(), Page.of(page, pageSize), from, to, doctorId, status, patientId, null);
    }

    @GetMapping("/appointments/slots")
    @PreAuthorize("hasAuthority('appointment.manage')")
    public List<java.time.Instant> staffSlots(@RequestParam UUID doctorId, @RequestParam UUID branchId, @RequestParam UUID serviceId, @RequestParam LocalDate date) {
        return slots.slots(ClinicContext.tenantId(), doctorId, branchId, serviceId, date, false);
    }

    @PostMapping("/appointments")
    @PreAuthorize("hasAuthority('appointment.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> book(@RequestBody AppointmentService.BookReq r, Authentication a) { return appointments.book(ClinicContext.tenantId(), user(a), null, r); }

    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('appointment.manage')")
    public Map<String, Object> queue(@RequestParam(required = false) UUID doctorId) { return appointments.queue(ClinicContext.tenantId(), doctorId); }

    @PostMapping("/appointments/walk-in")
    @PreAuthorize("hasAuthority('appointment.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> walkIn(@RequestBody WalkInReq r, Authentication a) { return appointments.walkIn(ClinicContext.tenantId(), user(a), r.patientId(), r.doctorId(), r.branchId(), r.serviceId(), r.visitType()); }

    @PatchMapping("/appointments/{id}/visit-type")
    @PreAuthorize("hasAuthority('appointment.manage') or hasAuthority('medical_note.create')")
    public Map<String, Object> setVisitType(@PathVariable UUID id, @RequestBody VisitTypeReq r, Authentication a) { return appointments.setVisitType(ClinicContext.tenantId(), user(a), id, r.visitType()); }

    @PostMapping("/appointments/{id}/{action}")
    @PreAuthorize("hasAuthority('appointment.manage')")
    public Map<String, Object> action(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) StatusReq r, Authentication a) {
        UUID t = ClinicContext.tenantId();
        String reason = r == null ? null : r.reason();
        return switch (action) {
            case "confirm" -> appointments.transition(t, user(a), id, "CONFIRMED", reason);
            case "reject" -> appointments.transition(t, user(a), id, "REJECTED", reason);
            case "cancel" -> appointments.transition(t, user(a), id, "CANCELLED", reason);
            case "check-in" -> appointments.transition(t, user(a), id, "CHECKED_IN", reason);
            case "start" -> appointments.transition(t, user(a), id, "IN_PROGRESS", reason);
            case "complete" -> appointments.transition(t, user(a), id, "COMPLETED", reason);
            case "no-show" -> appointments.transition(t, user(a), id, "NO_SHOW", reason);
            case "call" -> appointments.call(t, user(a), id);
            case "mark-paid" -> appointments.markPaid(t, user(a), id);
            default -> throw com.platform.shared.BusinessException.notFound("RESOURCE_NOT_FOUND", "Unknown action");
        };
    }

    // ---- clinical ---------------------------------------------------------------------------------------------------------

    @PostMapping("/encounters")
    @PreAuthorize("hasAuthority('medical_note.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createEncounter(@RequestBody ClinicalService.EncounterReq r, Authentication a) { return clinical.createEncounter(ClinicContext.tenantId(), user(a), r); }

    @GetMapping("/encounters/{id}")
    @PreAuthorize("hasAuthority('patient.read') and hasAuthority('medical_note.create')")
    public Map<String, Object> encounter(@PathVariable UUID id, Authentication a) { return clinical.encounter(ClinicContext.tenantId(), user(a), id); }

    @PatchMapping("/encounters/{id}")
    @PreAuthorize("hasAuthority('medical_note.create')")
    public Map<String, Object> updateEncounter(@PathVariable UUID id, @RequestBody ClinicalService.EncounterPatch r, Authentication a) { return clinical.updateEncounter(ClinicContext.tenantId(), user(a), id, r); }

    @PostMapping("/encounters/{id}/vitals")
    @PreAuthorize("hasAuthority('medical_note.create') or hasAuthority('patient.update')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> vitals(@PathVariable UUID id, @RequestBody ClinicalService.VitalsReq r, Authentication a) {
        clinical.addVitals(ClinicContext.tenantId(), user(a), id, r);
        return Map.of("ok", true);
    }

    @PostMapping("/encounters/{id}/conditions")
    @PreAuthorize("hasAuthority('medical_note.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> condition(@PathVariable UUID id, @RequestBody ClinicalService.ConditionReq r, Authentication a) {
        clinical.addCondition(ClinicContext.tenantId(), user(a), id, r);
        return Map.of("ok", true);
    }

    @PostMapping("/encounters/{id}/notes")
    @PreAuthorize("hasAuthority('medical_note.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> note(@PathVariable UUID id, @RequestBody ClinicalService.NoteReq r, Authentication a) { return clinical.addNote(ClinicContext.tenantId(), user(a), id, r); }

    @PatchMapping("/notes/{id}")
    @PreAuthorize("hasAuthority('medical_note.create')")
    public Map<String, Object> editNote(@PathVariable UUID id, @RequestBody UpdateNoteReq r, Authentication a) { return clinical.updateNote(ClinicContext.tenantId(), user(a), id, r.content(), r.patientVisible()); }

    @PostMapping("/encounters/{id}/prescriptions")
    @PreAuthorize("hasAuthority('prescription.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> prescribe(@PathVariable UUID id, @RequestBody ClinicalService.PrescriptionReq r, Authentication a) { return clinical.createPrescription(ClinicContext.tenantId(), user(a), id, r); }

    @PostMapping("/prescriptions/{id}/issue")
    @PreAuthorize("hasAuthority('prescription.create')")
    public Map<String, Object> issue(@PathVariable UUID id, Authentication a) { return clinical.setPrescriptionStatus(ClinicContext.tenantId(), user(a), id, "ISSUED"); }

    @PostMapping("/prescriptions/{id}/cancel")
    @PreAuthorize("hasAuthority('prescription.delete')")
    public Map<String, Object> cancelPrescription(@PathVariable UUID id, Authentication a) { return clinical.setPrescriptionStatus(ClinicContext.tenantId(), user(a), id, "CANCELLED"); }

    @PostMapping("/encounters/{id}/lab-orders")
    @PreAuthorize("hasAuthority('lab_order.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> labOrder(@PathVariable UUID id, @RequestBody ClinicalService.LabOrderReq r, Authentication a) { return clinical.createLabOrder(ClinicContext.tenantId(), user(a), id, r); }

    @PostMapping("/lab-orders/{id}/results")
    @PreAuthorize("hasAuthority('lab_result.upload')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> labResult(@PathVariable UUID id, @RequestBody ClinicalService.LabResultReq r, Authentication a) { return clinical.addLabResult(ClinicContext.tenantId(), user(a), id, r); }

    @PostMapping("/lab-orders/{id}/review")
    @PreAuthorize("hasAuthority('lab_result.review')")
    public Map<String, Object> review(@PathVariable UUID id, @RequestBody(required = false) ReviewReq r, Authentication a) { return clinical.reviewLab(ClinicContext.tenantId(), user(a), id, r != null && r.shareWithPatient()); }
}

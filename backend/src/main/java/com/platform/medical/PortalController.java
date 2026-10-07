package com.platform.medical;

import com.platform.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Patient portal. A deliberately separate controller (spec 20): it never reuses staff endpoints, and every
 * patient id is checked against the caller's own record and dependents before anything is read or written.
 */
@RestController
@RequestMapping("/api/v1/portal")
@PreAuthorize("hasRole('PATIENT')")
public class PortalController {
    private final PatientService patients;
    private final AppointmentService appointments;
    private final ClinicalService clinical;

    public PortalController(PatientService patients, AppointmentService appointments, ClinicalService clinical) {
        this.patients = patients;
        this.appointments = appointments;
        this.clinical = clinical;
    }

    public record BookReq(UUID patientId, UUID doctorId, UUID branchId, UUID serviceId, java.time.Instant startAt, String paymentMethod, String patientNote) {}
    public record CancelReq(String reason) {}

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping("/patients")
    public List<Map<String, Object>> myPatients(Authentication a) { return patients.portalPatients(ClinicContext.tenantId(), user(a)); }

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(Authentication a) { return appointments.myQueue(ClinicContext.tenantId(), user(a)); }

    @PostMapping("/leave")
    public Map<String, Object> leave(Authentication a) {
        patients.leave(ClinicContext.tenantId(), user(a));
        return Map.of("ok", true);
    }

    @GetMapping("/appointments")
    public Map<String, Object> myAppointments(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize, Authentication a) {
        UUID t = ClinicContext.tenantId();
        return appointments.list(t, Page.of(page, pageSize), null, null, null, null, null, patients.accessibleIds(t, user(a)));
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> book(@RequestBody BookReq r, Authentication a) {
        Map<String, Object> out = appointments.book(ClinicContext.openTenantId(), user(a), user(a),
                new AppointmentService.BookReq(r.patientId(), r.doctorId(), r.branchId(), r.serviceId(), r.startAt(), r.paymentMethod(), r.patientNote(), "PATIENT_WEB"));
        out.remove("internalNote");
        return out;
    }

    @GetMapping("/appointments/{id}")
    public Map<String, Object> appointment(@PathVariable UUID id, Authentication a) { return appointments.getForPatientUser(ClinicContext.tenantId(), user(a), id); }

    @PostMapping("/appointments/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) CancelReq r, Authentication a) {
        return appointments.cancelByPatient(ClinicContext.tenantId(), user(a), id, r == null ? null : r.reason());
    }

    /** Everything the patient is allowed to see, newest first: visits, shared notes, issued prescriptions, labs, shared documents. */
    @GetMapping("/patients/{patientId}/timeline")
    public Map<String, Object> timeline(@PathVariable UUID patientId, Authentication a) { return clinical.patientTimeline(ClinicContext.tenantId(), user(a), patientId); }

    @PostMapping("/lab-orders/{id}/results")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadResult(@PathVariable UUID id, @RequestBody ClinicalService.LabResultReq r, Authentication a) { return clinical.addLabResultByPatient(ClinicContext.tenantId(), user(a), id, r); }
}

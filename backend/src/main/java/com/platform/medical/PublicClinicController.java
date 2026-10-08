package com.platform.medical;

import com.platform.billing.PaymentProvider;
import com.platform.billing.WebhookEventStore;
import com.platform.core.auth.TokenResponse;
import com.platform.shared.BusinessException;
import com.platform.shared.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.web.bind.annotation.*;

/** Public clinic website data, booking slots, portal claim, and the card webhook. */
@RestController
@RequestMapping("/api/v1/clinic")
public class PublicClinicController {
    private final ClinicSettingsService settings;
    private final SlotService slots;
    private final PatientService patients;
    private final AppointmentService appointments;
    private final List<PaymentProvider> providers;
    private final WebhookEventStore webhooks;

    public PublicClinicController(ClinicSettingsService settings, SlotService slots, PatientService patients, AppointmentService appointments, List<PaymentProvider> providers, WebhookEventStore webhooks) {
        this.settings = settings;
        this.slots = slots;
        this.patients = patients;
        this.appointments = appointments;
        this.providers = providers;
        this.webhooks = webhooks;
    }

    public record LinkRequest(@NotBlank String identifier, @NotBlank String password, @NotBlank String patientCode, @NotBlank String pin) {}

    @GetMapping("/public/profile")
    public Map<String, Object> profile() {
        UUID t = ClinicContext.tenantId();
        Map<String, Object> p = new java.util.LinkedHashMap<>(settings.profile(t));
        // the public site never needs internal booking-policy numbers beyond what visitors act on
        // Online booking needs a patient account; while patient accounts are off the public site offers no booking
        p.put("bookingEnabled", Boolean.TRUE.equals(p.get("bookingEnabled")) && Boolean.TRUE.equals(p.get("patientPortalEnabled")));
        p.put("doctors", settings.doctors(t));
        p.put("services", settings.services(t, true));
        p.put("branches", settings.branches(t, true));
        return p;
    }

    @GetMapping("/public/slots")
    public List<Instant> slots(@RequestParam UUID doctorId, @RequestParam UUID branchId, @RequestParam UUID serviceId, @RequestParam LocalDate date) {
        return slots.slots(ClinicContext.tenantId(), doctorId, branchId, serviceId, date, true);
    }

    /** Which weekdays the doctor works (ISO, 1 = Monday ... 7 = Sunday); the booking page greys out the other days. */
    @GetMapping("/public/working-days")
    public Map<String, Object> workingDays(@RequestParam UUID doctorId, @RequestParam UUID branchId) {
        return Map.of("weekdays", slots.workingWeekdays(ClinicContext.tenantId(), doctorId, branchId));
    }

    /** An existing account claims its patient record with password + the PIN the clinic gave it. */
    @PostMapping("/portal/link")
    public TokenResponse link(@Valid @RequestBody LinkRequest r, HttpServletRequest req) {
        return patients.link(ClinicContext.openTenantId(), r.identifier(), r.password(), r.patientCode(), r.pin(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/webhooks/{provider}")
    public Map<String, Object> webhook(@PathVariable String provider, @RequestBody String raw, @RequestHeader(value = "X-Signature", required = false) String signature) {
        UUID tenantId = ClinicContext.tenantId();
        PaymentProvider p = providers.stream().filter(x -> x.code().equals(provider)).findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Unknown provider"));
        if (!p.verifySignature(raw, signature)) throw BusinessException.unauthorized("INVALID_SIGNATURE", "Invalid webhook signature");
        Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(raw);
        if (body.get("eventId") == null || body.get("appointmentId") == null) throw BusinessException.badRequest("INVALID_WEBHOOK", "eventId and appointmentId are required");
        String key = "clinic:" + provider;
        String eventId = String.valueOf(body.get("eventId"));
        if (!webhooks.firstDelivery(key, eventId, raw)) return Map.of("result", "DUPLICATE");
        String result = "IGNORED";
        if ("payment.succeeded".equals(body.get("type")))
            result = appointments.markCardPaid(tenantId, UUID.fromString(String.valueOf(body.get("appointmentId"))), ((Number) body.get("amountMinor")).longValue()) ? "PROCESSED" : "DUPLICATE";
        webhooks.markProcessed(key, eventId, result);
        return Map.of("result", result);
    }
}

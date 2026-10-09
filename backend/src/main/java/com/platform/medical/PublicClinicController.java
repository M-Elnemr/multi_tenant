package com.platform.medical;

import com.platform.billing.PaymentProvider;
import com.platform.billing.WebhookEventStore;
import com.platform.shared.BusinessException;
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
    private final com.platform.shared.PatientPortalPolicy policy;

    public PublicClinicController(ClinicSettingsService settings, SlotService slots, PatientService patients, AppointmentService appointments, List<PaymentProvider> providers, WebhookEventStore webhooks, com.platform.shared.PatientPortalPolicy policy) {
        this.policy = policy;
        this.settings = settings;
        this.slots = slots;
        this.patients = patients;
        this.appointments = appointments;
        this.providers = providers;
        this.webhooks = webhooks;
    }

    @GetMapping("/public/profile")
    public Map<String, Object> profile() {
        UUID t = ClinicContext.tenantId();
        Map<String, Object> p = new java.util.LinkedHashMap<>(settings.profile(t));
        // the public site never needs internal booking-policy numbers beyond what visitors act on
        // Online booking is switched off for now (see PatientPortalPolicy)
        p.put("bookingEnabled", Boolean.TRUE.equals(p.get("bookingEnabled")) && policy.bookingEnabled());
        p.put("queueCount", appointments.queueCount(t));
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

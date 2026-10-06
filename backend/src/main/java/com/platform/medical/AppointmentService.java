package com.platform.medical;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.billing.PaymentProvider;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Booking and the appointment state machine (spec 34 / 54). Double bookings are blocked both here and by a DB exclusion constraint. */
@Service
public class AppointmentService {
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "REQUESTED", Set.of("PENDING_CONFIRMATION", "CONFIRMED", "CANCELLED"),
            "PENDING_CONFIRMATION", Set.of("CONFIRMED", "REJECTED", "CANCELLED"),
            "CONFIRMED", Set.of("CHECKED_IN", "CANCELLED", "NO_SHOW"),
            "CHECKED_IN", Set.of("IN_PROGRESS", "NO_SHOW"),
            "IN_PROGRESS", Set.of("COMPLETED"));

    public record BookReq(UUID patientId, UUID doctorId, UUID branchId, UUID serviceId, Instant startAt, String paymentMethod, String patientNote, String source) {}

    private final JdbcClient jdbc;
    private final SlotService slots;
    private final PatientService patients;
    private final EntitlementService ent;
    private final List<PaymentProvider> providers;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public AppointmentService(JdbcClient jdbc, SlotService slots, PatientService patients, EntitlementService ent, List<PaymentProvider> providers, AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.slots = slots;
        this.patients = patients;
        this.ent = ent;
        this.providers = providers;
        this.audit = audit;
        this.events = events;
    }

    /** byUser = the logged-in patient/guardian (applies booking rules); null = staff booking on behalf of the patient. */
    @Transactional
    public Map<String, Object> book(UUID tenantId, UUID actor, UUID byPatientUser, BookReq r) {
        boolean byPatient = byPatientUser != null;
        var profile = jdbc.sql("SELECT booking_enabled, take_new_patients, requires_confirmation, card_enabled, cash_enabled FROM medical.clinic_profiles WHERE tenant_id = :t").param("t", tenantId).query().singleRow();
        if (byPatient && !(Boolean) profile.get("booking_enabled")) throw BusinessException.forbidden("BOOKING_DISABLED", "Online booking is not available");
        if (byPatient) patients.requireAccessible(tenantId, byPatientUser, r.patientId());
        else if (jdbc.sql("SELECT count(*) FROM medical.patients WHERE id = :p AND tenant_id = :t AND status = 'ACTIVE'").param("p", r.patientId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found");
        if (byPatient && !(Boolean) profile.get("take_new_patients")
                && jdbc.sql("SELECT count(*) FROM medical.appointments WHERE patient_id = :p AND tenant_id = :t AND status = 'COMPLETED'").param("p", r.patientId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.forbidden("NOT_ACCEPTING_NEW_PATIENTS", "This clinic is not accepting new patients online");

        String method = r.paymentMethod() == null ? "CASH_AT_CLINIC" : r.paymentMethod();
        if (!Set.of("CARD", "CASH_AT_CLINIC").contains(method)) throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "Unsupported payment method");
        if (byPatient && ("CARD".equals(method) ? !(Boolean) profile.get("card_enabled") : !(Boolean) profile.get("cash_enabled")))
            throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "This payment method is not available at this clinic");

        String month = YearMonth.now(ZoneOffset.UTC).toString();
        ent.requireCapacity(tenantId, "max_monthly_appointments", ent.usage(tenantId, "appointments_monthly", month));

        ZoneId tz = slots.zone(tenantId);
        LocalDate date = r.startAt().atZone(tz).toLocalDate();
        if (!slots.slots(tenantId, r.doctorId(), r.branchId(), r.serviceId(), date, byPatient).contains(r.startAt()))
            throw BusinessException.conflict("APPOINTMENT_SLOT_UNAVAILABLE", "This time is not available");

        var svc = jdbc.sql("SELECT duration_minutes, price_minor, currency FROM medical.appointment_services WHERE id = :s AND tenant_id = :t").param("s", r.serviceId()).param("t", tenantId).query().singleRow();
        Long price = svc.get("price_minor") == null ? null : Long.valueOf(((Number) svc.get("price_minor")).longValue());
        if (price == null) price = jdbc.sql("SELECT default_appointment_fee_minor FROM medical.doctors WHERE id = :d").param("d", r.doctorId()).query(Long.class).optional().orElse(null);
        boolean card = "CARD".equals(method);
        if (card && (price == null || price <= 0)) throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "Card payment needs a price on the service");

        String status = card ? "REQUESTED" : (byPatient && (Boolean) profile.get("requires_confirmation") ? "PENDING_CONFIRMATION" : "CONFIRMED");
        String source = byPatient ? (r.source() != null && Set.of("PATIENT_APP", "PATIENT_WEB").contains(r.source()) ? r.source() : "PATIENT_WEB") : (r.source() != null && Set.of("RECEPTION", "PHONE", "ADMIN").contains(r.source()) ? r.source() : "RECEPTION");
        UUID id = UUID.randomUUID();
        Instant end = r.startAt().plus(Duration.ofMinutes(((Number) svc.get("duration_minutes")).longValue()));
        try {
            jdbc.sql("""
                    INSERT INTO medical.appointments (id, tenant_id, patient_id, doctor_id, branch_id, service_id, start_at, end_at, status, booking_source, payment_method, payment_required,
                        payment_status, price_minor, currency, patient_note, hold_expires_at, created_by)
                    VALUES (:id, :t, :p, :d, :b, :s, :st, :en, :status, :src, :pm, :pr, :ps, :price, :cur, :note, :hold, :cb)
                    """).param("id", id).param("t", tenantId).param("p", r.patientId()).param("d", r.doctorId()).param("b", r.branchId()).param("s", r.serviceId())
                    .param("st", java.sql.Timestamp.from(r.startAt())).param("en", java.sql.Timestamp.from(end)).param("status", status).param("src", source).param("pm", method)
                    .param("pr", card).param("ps", card ? "PENDING" : "UNPAID").param("price", price).param("cur", svc.get("currency")).param("note", r.patientNote())
                    .param("hold", card ? java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))) : null).param("cb", actor).update();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("APPOINTMENT_SLOT_UNAVAILABLE", "This time was just taken");   // exclusion constraint won the race
        }
        String checkoutUrl = null;
        if (card) {
            PaymentProvider provider = providers.stream().filter(p -> p.code().equals("mock")).findFirst().orElseThrow();
            var intent = provider.createPaymentIntent(tenantId, id, price, (String) svc.get("currency"), "appt-" + id);
            jdbc.sql("UPDATE medical.appointments SET payment_provider_id = :p WHERE id = :i").param("p", intent.providerPaymentId()).param("i", id).update();
            checkoutUrl = intent.checkoutUrl();
        }
        ent.increment(tenantId, "appointments_monthly", month, 1);
        audit.record(actor, tenantId, "APPOINTMENT_CREATED", "appointment", id, "{\"source\":\"" + source + "\"}");
        events.publishEvent(new MedicalEvents.AppointmentChanged(tenantId, id, r.patientId(), status));
        Map<String, Object> out = new java.util.LinkedHashMap<>(get(tenantId, id));
        if (checkoutUrl != null) out.put("checkoutUrl", checkoutUrl);
        return out;
    }

    // ---- transitions ---------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> transition(UUID tenantId, UUID actor, UUID id, String to, String reason) {
        var a = lock(tenantId, id);
        String from = (String) a.get("status");
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to))
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Cannot move an appointment from " + from + " to " + to);
        if ("CONFIRMED".equals(to) && "REQUESTED".equals(from) && "PENDING".equals(a.get("payment_status")))
            throw BusinessException.badRequest("PAYMENT_REQUIRED", "Waiting for the card payment");
        applyStatus(tenantId, actor, a, to, reason);
        return get(tenantId, id);
    }

    private void applyStatus(UUID tenantId, UUID actor, Map<String, Object> a, String to, String reason) {
        UUID id = (UUID) a.get("id");
        Integer queue = null;
        if ("CHECKED_IN".equals(to)) {
            queue = jdbc.sql("SELECT coalesce(max(queue_number), 0) + 1 FROM medical.appointments WHERE tenant_id = :t AND doctor_id = :d AND start_at::date = (SELECT start_at::date FROM medical.appointments WHERE id = :i)")
                    .param("t", tenantId).param("d", a.get("doctor_id")).param("i", id).query(Integer.class).single();
        }
        jdbc.sql("UPDATE medical.appointments SET status = :s, cancel_reason = coalesce(:r, cancel_reason), queue_number = coalesce(:q, queue_number), hold_expires_at = NULL, updated_at = now() WHERE id = :i")
                .param("s", to).param("r", "CANCELLED".equals(to) || "REJECTED".equals(to) ? reason : null).param("q", queue).param("i", id).update();
        audit.record(actor, tenantId, "APPOINTMENT_" + to, "appointment", id, null);
        events.publishEvent(new MedicalEvents.AppointmentChanged(tenantId, id, (UUID) a.get("patient_id"), to));
    }

    /** A patient (or their guardian) cancels their own appointment, within the clinic's cancellation window. */
    @Transactional
    public Map<String, Object> cancelByPatient(UUID tenantId, UUID userId, UUID id, String reason) {
        var a = lock(tenantId, id);
        patients.requireAccessible(tenantId, userId, (UUID) a.get("patient_id"));
        String from = (String) a.get("status");
        if (!Set.of("REQUESTED", "PENDING_CONFIRMATION", "CONFIRMED").contains(from))
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "This appointment can no longer be cancelled");
        int hours = jdbc.sql("SELECT cancellation_window_hours FROM medical.clinic_profiles WHERE tenant_id = :t").param("t", tenantId).query(Integer.class).single();
        if (((java.sql.Timestamp) a.get("start_at")).toInstant().isBefore(Instant.now().plus(Duration.ofHours(hours))))
            throw BusinessException.conflict("CANCELLATION_WINDOW_PASSED", "It is too late to cancel online; please call the clinic");
        applyStatus(tenantId, userId, a, "CANCELLED", reason);
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> markPaid(UUID tenantId, UUID actor, UUID id) {
        var a = lock(tenantId, id);
        if (!"CASH_AT_CLINIC".equals(a.get("payment_method")) || "PAID".equals(a.get("payment_status")))
            throw BusinessException.badRequest("INVALID_PAYMENT_STATE", "Only unpaid cash appointments can be marked paid");
        jdbc.sql("UPDATE medical.appointments SET payment_status = 'PAID', updated_at = now() WHERE id = :i").param("i", id).update();
        audit.record(actor, tenantId, "APPOINTMENT_PAID", "appointment", id, null);
        return get(tenantId, id);
    }

    /** Verified, idempotent card callback. Amount must match the server-side price. */
    @Transactional
    public boolean markCardPaid(UUID tenantId, UUID id, long amountMinor) {
        var a = lock(tenantId, id);
        if ("PAID".equals(a.get("payment_status"))) return false;
        long price = a.get("price_minor") == null ? -1 : ((Number) a.get("price_minor")).longValue();
        if (amountMinor != price) throw BusinessException.badRequest("PAYMENT_AMOUNT_MISMATCH", "Paid amount does not match the price");
        jdbc.sql("UPDATE medical.appointments SET payment_status = 'PAID', updated_at = now() WHERE id = :i").param("i", id).update();
        if ("REQUESTED".equals(a.get("status"))) {
            boolean needsOk = jdbc.sql("SELECT requires_confirmation FROM medical.clinic_profiles WHERE tenant_id = :t").param("t", tenantId).query(Boolean.class).single();
            applyStatus(tenantId, null, a, needsOk ? "PENDING_CONFIRMATION" : "CONFIRMED", null);
        }
        return true;
    }

    /** Unpaid card holds release their slot after 15 minutes. */
    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT1M")
    @Transactional
    public void releaseExpiredHolds() {
        for (var e : jdbc.sql("SELECT id, tenant_id FROM medical.appointments WHERE status = 'REQUESTED' AND payment_status = 'PENDING' AND hold_expires_at < now() FOR UPDATE SKIP LOCKED").query().listOfRows()) {
            var a = lock((UUID) e.get("tenant_id"), (UUID) e.get("id"));
            applyStatus((UUID) e.get("tenant_id"), null, a, "CANCELLED", "Payment not received in time");
        }
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT a.id, a.patient_id, p.first_name || ' ' || p.last_name AS patient_name, p.patient_code, a.doctor_id, d.display_name AS doctor_name, a.branch_id, b.name AS branch_name,
                   a.service_id, s.name AS service_name, a.start_at, a.end_at, a.status, a.booking_source, a.payment_method, a.payment_status, a.price_minor, a.currency,
                   a.patient_note, a.queue_number, a.created_at
            FROM medical.appointments a JOIN medical.patients p ON p.id = a.patient_id JOIN medical.doctors d ON d.id = a.doctor_id
            JOIN medical.clinic_branches b ON b.id = a.branch_id JOIN medical.appointment_services s ON s.id = a.service_id
            """;

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return Rows.camel(jdbc.sql(SELECT + " WHERE a.id = :i AND a.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Appointment not found")));
    }

    public Map<String, Object> getForPatientUser(UUID tenantId, UUID userId, UUID id) {
        Map<String, Object> a = get(tenantId, id);
        patients.requireAccessible(tenantId, userId, (UUID) a.get("patientId"));
        a.remove("patientName");
        return a;
    }

    public Map<String, Object> list(UUID tenantId, Page page, LocalDate from, LocalDate to, UUID doctorId, String status, UUID patientId, List<UUID> restrictPatients) {
        ZoneId tz = slots.zone(tenantId);
        Instant f = from == null ? null : from.atStartOfDay(tz).toInstant();
        Instant t = to == null ? null : to.plusDays(1).atStartOfDay(tz).toInstant();
        String where = " WHERE a.tenant_id = :t AND (CAST(:f AS timestamptz) IS NULL OR a.start_at >= CAST(:f AS timestamptz)) AND (CAST(:to AS timestamptz) IS NULL OR a.start_at < CAST(:to AS timestamptz))"
                + " AND (CAST(:d AS uuid) IS NULL OR a.doctor_id = CAST(:d AS uuid)) AND (CAST(:s AS varchar) IS NULL OR a.status = CAST(:s AS varchar)) AND (CAST(:p AS uuid) IS NULL OR a.patient_id = CAST(:p AS uuid))"
                + (restrictPatients != null ? " AND a.patient_id IN (:rp)" : "");
        var cnt = jdbc.sql("SELECT count(*) FROM medical.appointments a" + where).param("t", tenantId).param("f", f == null ? null : java.sql.Timestamp.from(f)).param("to", t == null ? null : java.sql.Timestamp.from(t))
                .param("d", doctorId).param("s", status).param("p", patientId);
        var q = jdbc.sql(SELECT + where + " ORDER BY a.start_at LIMIT :lim OFFSET :off").param("t", tenantId).param("f", f == null ? null : java.sql.Timestamp.from(f)).param("to", t == null ? null : java.sql.Timestamp.from(t))
                .param("d", doctorId).param("s", status).param("p", patientId).param("lim", page.pageSize()).param("off", page.offset());
        if (restrictPatients != null) {
            List<UUID> rp = restrictPatients.isEmpty() ? List.of(new UUID(0, 0)) : restrictPatients;
            cnt = cnt.param("rp", rp);
            q = q.param("rp", rp);
        }
        return page.wrap(Rows.camel(q.query().listOfRows()), cnt.query(Long.class).single());
    }

    private Map<String, Object> lock(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM medical.appointments WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Appointment not found"));
    }
}

package com.platform.medical;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clinic profile & booking rules, branches, doctors, services, weekly schedules and exceptions. */
@Service
public class ClinicSettingsService {
    private final JdbcClient jdbc;
    private final EntitlementService ent;
    private final AuditService audit;
    private final com.platform.shared.PaymentPolicy policy;

    public ClinicSettingsService(JdbcClient jdbc, EntitlementService ent, AuditService audit, com.platform.shared.PaymentPolicy policy) {
        this.policy = policy;
        this.jdbc = jdbc;
        this.ent = ent;
        this.audit = audit;
    }

    public Map<String, Object> profile(UUID tenantId) {
        Map<String, Object> m = Rows.camel(jdbc.sql("SELECT clinic_name, about, phone, email, address_text, booking_enabled, take_new_patients, requires_confirmation, minimum_booking_notice_minutes, maximum_days_ahead, cancellation_window_hours, card_enabled, cash_enabled FROM medical.clinic_profiles WHERE tenant_id = :t")
                .param("t", tenantId).query().singleRow());
        m.put("cardAvailable", policy.cardEnabled());
        m.put("cardEnabled", policy.cardEnabled() && Boolean.TRUE.equals(m.get("cardEnabled")));   // never advertised while the platform has card payments off
        return m;
    }

    @Transactional
    public Map<String, Object> updateProfile(UUID tenantId, UUID actor, Map<String, Object> f) {
        jdbc.sql("""
                UPDATE medical.clinic_profiles SET clinic_name = coalesce(:clinicName, clinic_name), about = coalesce(:about, about), phone = coalesce(:phone, phone),
                  email = coalesce(:email, email), address_text = coalesce(:addressText, address_text), booking_enabled = coalesce(:bookingEnabled, booking_enabled),
                  take_new_patients = coalesce(:takeNewPatients, take_new_patients), requires_confirmation = coalesce(:requiresConfirmation, requires_confirmation),
                  minimum_booking_notice_minutes = coalesce(:minNotice, minimum_booking_notice_minutes), maximum_days_ahead = coalesce(:maxDays, maximum_days_ahead),
                  cancellation_window_hours = coalesce(:cancelHours, cancellation_window_hours), card_enabled = coalesce(:cardEnabled, card_enabled),
                  cash_enabled = coalesce(:cashEnabled, cash_enabled), updated_at = now() WHERE tenant_id = :t
                """).param("t", tenantId).param("clinicName", str(f, "clinicName")).param("about", str(f, "about")).param("phone", str(f, "phone")).param("email", str(f, "email"))
                .param("addressText", str(f, "addressText")).param("bookingEnabled", bool(f, "bookingEnabled")).param("takeNewPatients", bool(f, "takeNewPatients"))
                .param("requiresConfirmation", bool(f, "requiresConfirmation")).param("minNotice", num(f, "minimumBookingNoticeMinutes")).param("maxDays", num(f, "maximumDaysAhead"))
                .param("cancelHours", num(f, "cancellationWindowHours")).param("cardEnabled", bool(f, "cardEnabled")).param("cashEnabled", bool(f, "cashEnabled")).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "clinic_profile", null, null);
        return profile(tenantId);
    }

    // ---- branches ---------------------------------------------------------------------------------------

    public List<Map<String, Object>> branches(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("SELECT id, name, code, address_line1, city, district, phone, is_active FROM medical.clinic_branches WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY created_at")
                .param("t", tenantId).param("a", onlyActive).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> createBranch(UUID tenantId, UUID actor, String name, String code, String address, String city, String phone) {
        if (name == null || name.isBlank() || code == null || code.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name and code are required");
        ent.requireCapacity(tenantId, "max_branches", jdbc.sql("SELECT count(*) FROM medical.clinic_branches WHERE tenant_id = :t AND is_active").param("t", tenantId).query(Long.class).single());
        try {
            UUID id = jdbc.sql("INSERT INTO medical.clinic_branches (tenant_id, name, code, address_line1, city, phone) VALUES (:t, :n, :c, coalesce(:a,''), coalesce(:ci,''), :p) RETURNING id")
                    .param("t", tenantId).param("n", name.trim()).param("c", code.trim().toUpperCase()).param("a", address).param("ci", city).param("p", phone).query(UUID.class).single();
            audit.record(actor, tenantId, "BRANCH_CREATED", "clinic_branch", id, null);
            return branches(tenantId, false).stream().filter(b -> id.equals(b.get("id"))).findFirst().orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("BRANCH_CODE_TAKEN", "Branch code already exists");
        }
    }

    // ---- doctors ----------------------------------------------------------------------------------------

    public List<Map<String, Object>> doctors(UUID tenantId) {
        return Rows.camel(jdbc.sql("""
                SELECT d.id, d.display_name, d.bio, d.gender, d.consultation_duration_minutes, d.default_appointment_fee_minor, d.currency, d.verification_status, d.other_specialty,
                  coalesce((SELECT json_agg(json_build_object('code', s.code, 'nameAr', s.name_ar, 'nameEn', s.name_en))::text FROM medical.doctor_specialties ds JOIN medical.specialties s ON s.id = ds.specialty_id WHERE ds.doctor_id = d.id), '[]') AS specialties
                FROM medical.doctors d WHERE d.tenant_id = :t AND d.is_active ORDER BY d.created_at
                """).param("t", tenantId).query().listOfRows()).stream().peek(m -> m.put("specialties", Rows.jsonList(m.get("specialties")))).toList();
    }

    /** The calling user's own doctor profile, or 403 if they are not a doctor of this clinic. */
    public Map<String, Object> myDoctor(UUID tenantId, UUID userId) {
        UUID doctorId = jdbc.sql("SELECT id FROM medical.doctors WHERE tenant_id = :t AND user_id = :u AND is_active").param("t", tenantId).param("u", userId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.forbidden("NOT_A_DOCTOR", "You do not have a doctor profile in this clinic"));
        return doctors(tenantId).stream().filter(d -> doctorId.equals(d.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> updateMyDoctorProfile(UUID tenantId, UUID userId, Map<String, Object> f, List<String> specialtyCodes) {
        UUID doctorId = jdbc.sql("SELECT id FROM medical.doctors WHERE tenant_id = :t AND user_id = :u AND is_active").param("t", tenantId).param("u", userId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.forbidden("NOT_A_DOCTOR", "You do not have a doctor profile in this clinic"));
        jdbc.sql("""
                UPDATE medical.doctors SET display_name = coalesce(:dn, display_name), bio = coalesce(:bio, bio), gender = coalesce(:g, gender),
                  license_number = coalesce(:ln, license_number), consultation_duration_minutes = coalesce(:cd, consultation_duration_minutes),
                  default_appointment_fee_minor = coalesce(:fee, default_appointment_fee_minor), updated_at = now() WHERE id = :d
                """).param("dn", str(f, "displayName")).param("bio", str(f, "bio")).param("g", str(f, "gender")).param("ln", str(f, "licenseNumber"))
                .param("cd", num(f, "consultationDurationMinutes")).param("fee", num(f, "defaultAppointmentFeeMinor")).param("d", doctorId).update();
        if (specialtyCodes != null) {
            if (specialtyCodes.size() > 5) throw BusinessException.badRequest("TOO_MANY_CATEGORIES", "Choose up to 5");
            setSpecialties(jdbc, doctorId, specialtyCodes, str(f, "otherSpecialty"));
        }
        audit.record(userId, tenantId, "SETTINGS_CHANGED", "doctor", doctorId, null);
        return doctors(tenantId).stream().filter(d -> doctorId.equals(d.get("id"))).findFirst().orElseThrow();
    }

    public List<Map<String, Object>> specialties() {
        return listSpecialties(jdbc);
    }

    /** Most common first, then A-Z; "other" is last. */
    static List<Map<String, Object>> listSpecialties(JdbcClient jdbc) {
        return Rows.camel(jdbc.sql("SELECT code, name_ar, name_en, popular FROM medical.specialties WHERE is_active ORDER BY sort_order, name_en").query().listOfRows());
    }

    /** Replaces a doctor's specialties. "other" requires the typed name, which is kept only while "other" is chosen. */
    static void setSpecialties(JdbcClient jdbc, UUID doctorId, List<String> codes, String other) {
        jdbc.sql("DELETE FROM medical.doctor_specialties WHERE doctor_id = :d").param("d", doctorId).update();
        for (String code : codes) {
            int n = jdbc.sql("INSERT INTO medical.doctor_specialties (doctor_id, specialty_id) SELECT :d, id FROM medical.specialties WHERE code = :c AND is_active").param("d", doctorId).param("c", code).update();
            if (n == 0) throw BusinessException.badRequest("UNKNOWN_SPECIALTY", "Unknown specialty " + code);
        }
        boolean hasOther = codes.contains("other");
        if (hasOther && (other == null || other.trim().length() < 2)) throw BusinessException.badRequest("OTHER_CATEGORY_REQUIRED", "Please type the name for \"Other\"");
        jdbc.sql("UPDATE medical.doctors SET other_specialty = :o WHERE id = :d").param("o", hasOther ? other.trim() : null).param("d", doctorId).update();
    }

    // ---- services ----------------------------------------------------------------------------------------

    public List<Map<String, Object>> services(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("SELECT id, name, description, duration_minutes, price_minor, currency, is_active FROM medical.appointment_services WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY name")
                .param("t", tenantId).param("a", onlyActive).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> createService(UUID tenantId, UUID actor, String name, String description, int durationMinutes, Long priceMinor) {
        if (name == null || name.isBlank() || durationMinutes < 5 || durationMinutes > 480 || (priceMinor != null && priceMinor < 0))
            throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid service");
        UUID id = jdbc.sql("INSERT INTO medical.appointment_services (tenant_id, name, description, duration_minutes, price_minor) VALUES (:t, :n, :d, :m, :p) RETURNING id")
                .param("t", tenantId).param("n", name.trim()).param("d", description).param("m", durationMinutes).param("p", priceMinor).query(UUID.class).single();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "appointment_service", id, null);
        return services(tenantId, false).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void setServiceActive(UUID tenantId, UUID id, boolean active) {
        if (jdbc.sql("UPDATE medical.appointment_services SET is_active = :a WHERE id = :i AND tenant_id = :t").param("a", active).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    // ---- schedules ---------------------------------------------------------------------------------------

    public List<Map<String, Object>> schedules(UUID tenantId, UUID doctorId) {
        return Rows.camel(jdbc.sql("SELECT id, doctor_id, branch_id, weekday, start_local_time, end_local_time, slot_duration_minutes, buffer_minutes, effective_from, effective_to, is_active FROM medical.doctor_schedules WHERE tenant_id = :t AND (CAST(:d AS uuid) IS NULL OR doctor_id = CAST(:d AS uuid)) ORDER BY doctor_id, weekday, start_local_time")
                .param("t", tenantId).param("d", doctorId).query().listOfRows());
    }

    public record ScheduleReq(UUID doctorId, UUID branchId, int weekday, String startTime, String endTime, Integer slotDurationMinutes, Integer bufferMinutes, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    @Transactional
    public Map<String, Object> createSchedule(UUID tenantId, UUID actor, ScheduleReq r) {
        requireDoctorAndBranch(tenantId, r.doctorId(), r.branchId());
        LocalTime start, end;
        try { start = LocalTime.parse(r.startTime()); end = LocalTime.parse(r.endTime()); } catch (Exception e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Times must be HH:mm"); }
        if (r.weekday() < 1 || r.weekday() > 7 || !end.isAfter(start)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid weekday or time range");
        int slot = r.slotDurationMinutes() == null ? 30 : r.slotDurationMinutes();
        int buffer = r.bufferMinutes() == null ? 0 : r.bufferMinutes();
        if (slot < 5 || buffer < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid slot settings");
        UUID id = jdbc.sql("""
                INSERT INTO medical.doctor_schedules (tenant_id, doctor_id, branch_id, weekday, start_local_time, end_local_time, slot_duration_minutes, buffer_minutes, effective_from, effective_to)
                VALUES (:t, :d, :b, :w, :s, :e, :sl, :bu, :ef, :et) RETURNING id
                """).param("t", tenantId).param("d", r.doctorId()).param("b", r.branchId()).param("w", r.weekday()).param("s", java.sql.Time.valueOf(start)).param("e", java.sql.Time.valueOf(end))
                .param("sl", slot).param("bu", buffer).param("ef", r.effectiveFrom() == null ? null : java.sql.Date.valueOf(r.effectiveFrom())).param("et", r.effectiveTo() == null ? null : java.sql.Date.valueOf(r.effectiveTo())).query(UUID.class).single();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "doctor_schedule", id, null);
        return schedules(tenantId, r.doctorId()).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteSchedule(UUID tenantId, UUID id) {
        if (jdbc.sql("DELETE FROM medical.doctor_schedules WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    public record ExceptionReq(UUID doctorId, UUID branchId, LocalDate date, String type, String startTime, String endTime, String reason) {}

    @Transactional
    public void createException(UUID tenantId, UUID actor, ExceptionReq r) {
        if (!Set.of("DAY_OFF", "CUSTOM_HOURS", "HOLIDAY", "FULL_BOOKED").contains(r.type()) || r.date() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid exception");
        requireDoctorAndBranch(tenantId, r.doctorId(), r.branchId());
        if ("CUSTOM_HOURS".equals(r.type()) && (r.startTime() == null || r.endTime() == null)) throw BusinessException.badRequest("VALIDATION_ERROR", "CUSTOM_HOURS needs startTime and endTime");
        jdbc.sql("INSERT INTO medical.schedule_exceptions (tenant_id, doctor_id, branch_id, exception_date, start_local_time, end_local_time, type, reason) VALUES (:t,:d,:b,:dt,:s,:e,:ty,:r)")
                .param("t", tenantId).param("d", r.doctorId()).param("b", r.branchId()).param("dt", java.sql.Date.valueOf(r.date()))
                .param("s", r.startTime() == null ? null : java.sql.Time.valueOf(LocalTime.parse(r.startTime()))).param("e", r.endTime() == null ? null : java.sql.Time.valueOf(LocalTime.parse(r.endTime())))
                .param("ty", r.type()).param("r", r.reason()).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "schedule_exception", null, null);
    }

    private void requireDoctorAndBranch(UUID tenantId, UUID doctorId, UUID branchId) {
        if (jdbc.sql("SELECT count(*) FROM medical.doctors WHERE id = :d AND tenant_id = :t AND is_active").param("d", doctorId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Doctor not found");
        if (branchId != null && jdbc.sql("SELECT count(*) FROM medical.clinic_branches WHERE id = :b AND tenant_id = :t").param("b", branchId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Branch not found");
    }

    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    private static Boolean bool(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Boolean b ? b : null; }
    private static Integer num(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : null; }
}

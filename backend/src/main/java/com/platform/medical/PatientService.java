package com.platform.medical;

import com.platform.audit.AuditService;
import com.platform.core.auth.AccountAuthService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient registry + patient accounts. A patient account is created ONLY by a clinic (the patient cannot register themselves):
 *  - new mobile number  -> the clinic gives a temporary password; the patient must change it at first sign-in;
 *  - mobile number that already has an account (made by another clinic) -> this clinic is simply added to that account;
 *    the patient keeps their own password and each clinic keeps its own, separate record.
 */
@Service
public class PatientService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record PatientReq(String firstName, String lastName, String phone, String email, LocalDate dateOfBirth, String sex, String addressText,
                             String bloodType, String notesInternal, UUID guardianPatientId, String relationship, String initialPassword, Boolean claimWithCode,
                             Integer ageYears, Integer ageMonths) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AccountAuthService accounts;
    private final AuditService audit;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.platform.shared.PatientPortalPolicy portalPolicy;

    public PatientService(JdbcClient jdbc, PasswordEncoder encoder, AccountAuthService accounts, AuditService audit, org.springframework.context.ApplicationEventPublisher events,
                          com.platform.shared.PatientPortalPolicy portalPolicy) {
        this.events = events;
        this.portalPolicy = portalPolicy;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actor, PatientReq r) {
        if (r.firstName() == null || r.firstName().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "First name is required");
        if (r.phone() == null || r.phone().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Mobile number is required");
        String phone = PhoneNormalizer.normalize(r.phone());
        LocalDate[] dob = resolveDob(tenantId, r);

        boolean portalOn = portalPolicy.enabled();   // PATIENT_PORTAL_ENABLED=false keeps patients as plain records without any account
        UUID guardianUser = null;
        if (portalOn && r.guardianPatientId() != null) {
            guardianUser = jdbc.sql("SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", r.guardianPatientId()).param("t", tenantId).query(UUID.class).optional()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Guardian patient not found"));
            if (guardianUser == null) throw BusinessException.badRequest("GUARDIAN_NOT_LINKED", "The guardian has no account yet");
        }

        UUID accountId = null;
        UUID relinkPatientId = null;
        String portal = "NONE";
        if (portalOn && guardianUser == null) {
            boolean hasAccount = jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE phone = :p").param("p", phone).query(Long.class).single() > 0;
            if (!hasAccount && (r.initialPassword() == null || r.initialPassword().isBlank()))
                throw BusinessException.badRequest("TEMP_PASSWORD_REQUIRED", "Give the patient a temporary password (they will change it when they sign in)");
            var acc = accounts.ensurePatientAccount(phone, (r.firstName().trim() + " " + (r.lastName() == null ? "" : r.lastName().trim())).trim(), r.initialPassword());
            accountId = acc.id();
            Long linked = jdbc.sql("SELECT count(*) FROM medical.patients WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", accountId).query(Long.class).single();
            if (linked > 0) throw BusinessException.conflict("ALREADY_PATIENT", "This person is already a patient of this clinic");
            // This clinic may already hold a file for the same phone that lost its account link (e.g. the patient left earlier): reconnect it instead of duplicating.
            relinkPatientId = jdbc.sql("SELECT id FROM medical.patients WHERE tenant_id = :t AND phone = :p AND user_id IS NULL ORDER BY created_at LIMIT 1")
                    .param("t", tenantId).param("p", phone).query(UUID.class).optional().orElse(null);
            portal = acc.created() ? "PASSWORD_SET" : "ASSIGNED";
        }
        if (relinkPatientId != null) {
            jdbc.sql("UPDATE medical.patients SET user_id = :u, updated_at = now() WHERE id = :p AND tenant_id = :t").param("u", accountId).param("p", relinkPatientId).param("t", tenantId).update();
            audit.record(actor, tenantId, "ACCESS_GRANTED", "patient", relinkPatientId, "{\"mode\":\"relink\"}");
            events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, relinkPatientId, "CLINIC_ADDED"));
            Map<String, Object> out = new LinkedHashMap<>(summary(tenantId, relinkPatientId));
            out.put("portalAccess", portal);
            return out;
        }

        UUID id = null;
        for (int i = 0; i < 5 && id == null; i++) {
            try {
                id = jdbc.sql("""
                        INSERT INTO medical.patients (tenant_id, user_id, patient_code, first_name, last_name, date_of_birth, dob_estimated, sex, phone, address_text, blood_type, notes_internal, created_by)
                        VALUES (:t, :u, :c, :fn, :ln, :dob, :est, :sex, :ph, :ad, :bt, :ni, :cb) RETURNING id
                        """).param("t", tenantId).param("u", accountId).param("c", newCode()).param("fn", r.firstName().trim()).param("ln", r.lastName() == null ? "" : r.lastName().trim())
                        .param("dob", dob[0] == null ? null : java.sql.Date.valueOf(dob[0])).param("est", dob[1] != null).param("sex", r.sex()).param("ph", phone)
                        .param("ad", r.addressText()).param("bt", r.bloodType()).param("ni", r.notesInternal())
                        .param("cb", actor).query(UUID.class).single();
            } catch (DuplicateKeyException e) {
                // patient code collision: try another random code
            }
        }
        if (id == null) throw new IllegalStateException("Could not allocate a patient code");
        if (guardianUser != null) {
            jdbc.sql("INSERT INTO medical.patient_guardians (tenant_id, patient_id, guardian_user_id, relationship, is_primary) VALUES (:t, :p, :g, :r, TRUE)")
                    .param("t", tenantId).param("p", id).param("g", guardianUser).param("r", r.relationship() == null ? "GUARDIAN" : r.relationship()).update();
            portal = "GUARDIAN";
        }
        audit.record(actor, tenantId, "PATIENT_CREATED", "patient", id, null);
        Map<String, Object> out = new LinkedHashMap<>(summary(tenantId, id));
        out.put("portalAccess", portal);
        if ("ASSIGNED".equals(portal)) events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, id, "CLINIC_ADDED"));
        return out;
    }

    // ---- reads / updates -----------------------------------------------------------------------------------------------

    public Map<String, Object> search(UUID tenantId, String q, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String code = q == null ? null : q.trim().toUpperCase();
        String phone = q == null ? null : q.trim().replaceAll("[\\s\\-]", "");
        String where = "tenant_id = :t AND status = 'ACTIVE' AND (CAST(:like AS varchar) IS NULL OR (first_name || ' ' || last_name) ILIKE CAST(:like AS varchar) OR patient_code = :code OR phone LIKE :ph)";
        long total = jdbc.sql("SELECT count(*) FROM medical.patients WHERE " + where).param("t", tenantId).param("like", like).param("code", code).param("ph", phone == null ? "" : "%" + phone.replaceFirst("^0", "") + "%").query(Long.class).single();
        var rows = jdbc.sql("SELECT id, patient_code, first_name, last_name, date_of_birth, sex, phone, (user_id IS NOT NULL) AS has_portal, created_at FROM medical.patients WHERE " + where + " ORDER BY first_name, last_name LIMIT :lim OFFSET :off")
                .param("t", tenantId).param("like", like).param("code", code).param("ph", phone == null ? "" : "%" + phone.replaceFirst("^0", "") + "%").param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        ZoneId tz = zone(tenantId);
        return page.wrap(Rows.camel(rows).stream().map(m -> withAge(m, tz)).toList(), total);
    }

    Map<String, Object> summary(UUID tenantId, UUID id) {
        return withAge(Rows.camel(jdbc.sql("SELECT id, patient_code, first_name, last_name, date_of_birth, dob_estimated, sex, phone, address_text, blood_type, (user_id IS NOT NULL) AS has_portal, status, created_at FROM medical.patients WHERE id = :p AND tenant_id = :t")
                .param("p", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found"))), zone(tenantId));
    }

    private ZoneId zone(UUID tenantId) {
        return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo"));
    }

    /** Adds ageYears/ageMonths (from the stored birth date, estimated or not) so the UI never has to show a date of birth. */
    private static Map<String, Object> withAge(Map<String, Object> m, ZoneId tz) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        Object d = m.get("dateOfBirth");
        LocalDate dob = d instanceof LocalDate ld ? ld : d instanceof java.sql.Date sd ? sd.toLocalDate() : d instanceof String str && !str.isBlank() ? LocalDate.parse(str) : null;
        if (dob != null && !dob.isAfter(LocalDate.now(tz))) {
            java.time.Period p = java.time.Period.between(dob, LocalDate.now(tz));
            out.put("ageYears", p.getYears());
            out.put("ageMonths", p.getMonths());
        }
        return out;
    }

    /** {date of birth, estimateMarker}: an exact date wins; otherwise age in years (0-130) + months (0-11) becomes an estimated date. {null,null} when neither was given. */
    private LocalDate[] resolveDob(UUID tenantId, PatientReq r) {
        if (r.ageYears() != null || r.ageMonths() != null) {
            int y = r.ageYears() == null ? 0 : r.ageYears();
            int mo = r.ageMonths() == null ? 0 : r.ageMonths();
            if (y < 0 || y > 130 || mo < 0 || mo > 11) throw BusinessException.badRequest("VALIDATION_ERROR", "Age: years 0-130 and months 0-11");
            LocalDate est = LocalDate.now(zone(tenantId)).minusYears(y).minusMonths(mo);
            return new LocalDate[] {est, est};
        }
        return new LocalDate[] {r.dateOfBirth(), null};
    }

    /** Demographic record for staff (clinical data lives in the timeline, behind clinical permissions). Every read of a patient record is audited (spec 19). */
    public Map<String, Object> detail(UUID tenantId, UUID actor, UUID id) {
        Map<String, Object> out = new LinkedHashMap<>(summary(tenantId, id));
        out.putAll(Rows.camel(jdbc.sql("SELECT notes_internal, emergency_contact_json::text AS emergency_contact FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", id).param("t", tenantId).query().singleRow()));
        audit.record(actor, tenantId, "PATIENT_VIEWED", "patient", id, null);
        return out;
    }

    @Transactional
    public Map<String, Object> update(UUID tenantId, UUID actor, UUID id, PatientReq r) {
        summary(tenantId, id);
        if (r.firstName() != null && r.firstName().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name is required");
        LocalDate[] dob = resolveDob(tenantId, r);
        String phone = r.phone() == null || r.phone().isBlank() ? null : PhoneNormalizer.normalize(r.phone());
        jdbc.sql("""
                UPDATE medical.patients SET first_name = coalesce(:fn, first_name), last_name = coalesce(:ln, last_name), date_of_birth = coalesce(:dob, date_of_birth),
                  dob_estimated = CASE WHEN CAST(:dob AS date) IS NULL THEN dob_estimated ELSE :est END, sex = coalesce(:sex, sex), phone = coalesce(:ph, phone),
                  address_text = coalesce(:ad, address_text), blood_type = coalesce(:bt, blood_type), notes_internal = coalesce(:ni, notes_internal), updated_at = now()
                WHERE id = :p AND tenant_id = :t
                """).param("fn", r.firstName()).param("ln", r.lastName()).param("dob", dob[0] == null ? null : java.sql.Date.valueOf(dob[0])).param("est", dob[1] != null).param("sex", r.sex())
                .param("ph", phone).param("ad", r.addressText()).param("bt", r.bloodType()).param("ni", r.notesInternal()).param("p", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "PATIENT_UPDATED", "patient", id, null);
        return summary(tenantId, id);
    }

    /**
     * The clinic issues a NEW temporary password (e.g. the patient forgot it). Only while the patient has not yet chosen their own password:
     * once they did, the password is theirs alone. The patient must change the temporary one at their next sign-in. All sessions are revoked.
     */
    @Transactional
    public void setPassword(UUID tenantId, UUID actor, UUID patientId, String newPassword) {
        accounts.validatePassword(newPassword);
        UUID accountId = jdbc.sql("SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", patientId).param("t", tenantId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found"));
        if (accountId == null) throw BusinessException.badRequest("PHONE_REQUIRED", "This patient has no account yet");
        boolean temp = jdbc.sql("SELECT must_change_password FROM medical.patient_accounts WHERE id = :u").param("u", accountId).query(Boolean.class).single();
        if (!temp) throw BusinessException.forbidden("PASSWORD_SET_NOT_ALLOWED", "This patient already chose their own password; it can no longer be changed by the clinic");
        jdbc.sql("UPDATE medical.patient_accounts SET password_hash = :h, must_change_password = TRUE, updated_at = now() WHERE id = :u").param("h", encoder.encode(newPassword)).param("u", accountId).update();
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", accountId).update();
        audit.record(actor, tenantId, "PATIENT_PASSWORD_SET", "patient", patientId, null);
    }

    /** A patient leaves a clinic: they lose access to its portal; the clinic keeps its medical record (it must be retained). */
    @Transactional
    public void leave(UUID tenantId, UUID accountId) {
        int n = jdbc.sql("UPDATE medical.patients SET user_id = NULL, updated_at = now() WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", accountId).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        jdbc.sql("DELETE FROM medical.patient_guardians WHERE tenant_id = :t AND guardian_user_id = :u").param("t", tenantId).param("u", accountId).update();
        audit.record(accountId, tenantId, "ACCESS_REVOKED", "patient_account", accountId, "{\"by\":\"patient\"}");
    }

    // ---- portal access control -------------------------------------------------------------------------------------------

    /** Patients this account may act for in this clinic: their own record plus dependents they are guardian of. */
    public List<UUID> accessibleIds(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                SELECT id FROM medical.patients WHERE tenant_id = :t AND status = 'ACTIVE' AND (user_id = :u OR id IN (SELECT patient_id FROM medical.patient_guardians WHERE tenant_id = :t AND guardian_user_id = :u))
                """).param("t", tenantId).param("u", userId).query(UUID.class).list();
    }

    /** 404 (not 403) when the patient is not one of the caller's own - never confirms another patient exists. */
    public void requireAccessible(UUID tenantId, UUID userId, UUID patientId) {
        if (!accessibleIds(tenantId, userId).contains(patientId)) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found");
    }

    /** Owner patient of a prescription of this clinic, or 404 (so ids of other clinics' prescriptions reveal nothing). */
    public UUID prescriptionPatient(UUID tenantId, UUID prescriptionId) {
        return jdbc.sql("SELECT patient_id FROM medical.prescriptions WHERE id = :i AND tenant_id = :t").param("i", prescriptionId).param("t", tenantId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Prescription not found"));
    }

    public List<Map<String, Object>> portalPatients(UUID tenantId, UUID userId) {
        return accessibleIds(tenantId, userId).stream().map(id -> summary(tenantId, id)).toList();
    }

    private static String newCode() { return "PAT-" + random(4) + "-" + random(4); }

        private static String random(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}

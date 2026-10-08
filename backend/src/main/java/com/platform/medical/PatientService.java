package com.platform.medical;

import com.platform.audit.AuditService;
import com.platform.core.auth.ActivationService;
import com.platform.core.auth.AuthService;
import com.platform.core.auth.LoginThrottle;
import com.platform.core.auth.TokenResponse;
import com.platform.core.rbac.RbacService;
import com.platform.core.user.Membership;
import com.platform.core.user.MembershipRepository;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
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
 * Patient registry + portal access without SMS OTP:
 *  - brand-new phone  -> invited account + activation PIN (patient creates own password);
 *  - phone that already has an account -> record stays unlinked until that person proves ownership
 *    with their password AND a link PIN the clinic gives them (so a typo/wrong phone can't expose a record).
 * The patient code alone never authenticates anyone.
 */
@Service
public class PatientService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_LINK_ATTEMPTS = 5;

    public record PatientReq(String firstName, String lastName, String phone, String email, LocalDate dateOfBirth, String sex, String addressText,
                             String bloodType, String notesInternal, UUID guardianPatientId, String relationship, String initialPassword, Boolean claimWithCode,
                             Integer ageYears, Integer ageMonths) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final ActivationService activation;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.platform.shared.PatientPortalPolicy portalPolicy;

    public PatientService(JdbcClient jdbc, UserRepository users, MembershipRepository memberships, RbacService rbac, ActivationService activation,
                          PasswordEncoder encoder, AuthService auth, LoginThrottle throttle, AuditService audit,
                          org.springframework.context.ApplicationEventPublisher events, com.platform.shared.PatientPortalPolicy portalPolicy) {
        this.events = events;
        this.portalPolicy = portalPolicy;
        this.jdbc = jdbc;
        this.users = users;
        this.memberships = memberships;
        this.rbac = rbac;
        this.activation = activation;
        this.encoder = encoder;
        this.auth = auth;
        this.throttle = throttle;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actor, PatientReq r) {
        if (r.firstName() == null || r.firstName().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "First name is required");
        if (r.phone() == null || r.phone().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Mobile number is required");
        String phone = PhoneNormalizer.normalize(r.phone());
        String email = null;   // patients are reached by mobile; no email is kept
        boolean portalOn = portalPolicy.enabled();
        LocalDate[] dob = resolveDob(tenantId, r);

        UUID guardianUser = null;
        if (portalOn && r.guardianPatientId() != null) {
            guardianUser = jdbc.sql("SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", r.guardianPatientId()).param("t", tenantId).query(UUID.class).optional()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Guardian patient not found"));
            if (guardianUser == null) throw BusinessException.badRequest("GUARDIAN_NOT_LINKED", "The guardian has no portal account yet");
        }

        UUID userId = null;
        UUID relinkPatientId = null;
        String activationPin = null;
        String linkPin = null;
        String portal = "NONE";
        boolean claimOnly = portalOn && Boolean.TRUE.equals(r.claimWithCode());
        if (portalOn && phone != null && guardianUser == null) {
            User existing = users.findByPhone(phone).orElse(null);
            if (existing == null) {
                User u = new User();
                u.setPhone(phone);
                u.setEmail(email != null && users.findByEmailIgnoreCase(email).isEmpty() ? email : null);
                u.setFirstName(r.firstName());
                u.setLastName(r.lastName() == null ? "" : r.lastName());
                if (r.initialPassword() != null && !r.initialPassword().isBlank()) {
                    // The clinic chooses the first password; the patient can change it any time (POST /auth/change-password).
                    auth.validatePassword(r.initialPassword());
                    u.setPasswordHash(encoder.encode(r.initialPassword()));
                    u.setStatus(User.Status.ACTIVE);
                    portal = "PASSWORD_SET";
                } else {
                    u.setStatus(User.Status.INVITED);
                }
                u = users.saveAndFlush(u);
                userId = u.getId();
                joinAsPatient(userId, tenantId);
                if (u.getStatus() == User.Status.INVITED) {
                    activationPin = activation.issue(userId, tenantId, actor);
                    portal = "ACTIVATION_PIN";
                }
            } else if (claimOnly) {
                linkPin = randomPin();   // the person must prove ownership (password + this code) to claim the record
                portal = "LINK_PIN";
            } else {
                // The person already has an account (created at another clinic): attach it to THIS clinic. This clinic gets its own,
                // empty record; nothing from other clinics is reachable, and the person can leave the clinic from their app.
                if (r.initialPassword() != null && !r.initialPassword().isBlank())
                    throw BusinessException.badRequest("ACCOUNT_EXISTS", "This phone already has an account; its owner keeps their own password");
                Long linked = jdbc.sql("SELECT count(*) FROM medical.patients WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", existing.getId()).query(Long.class).single();
                if (linked > 0) throw BusinessException.conflict("ALREADY_PATIENT", "This person is already a patient of this clinic");
                userId = existing.getId();
                relinkPatientId = jdbc.sql("SELECT id FROM medical.patients WHERE tenant_id = :t AND phone = :p AND user_id IS NULL ORDER BY created_at LIMIT 1")
                        .param("t", tenantId).param("p", phone).query(UUID.class).optional().orElse(null);
                joinAsPatient(userId, tenantId);
                portal = "ASSIGNED";
            }
        }
        if (relinkPatientId != null) {
            // This clinic already holds an unlinked file for the same phone (e.g. the patient left earlier): reconnect it instead of duplicating.
            jdbc.sql("UPDATE medical.patients SET user_id = :u, updated_at = now() WHERE id = :p AND tenant_id = :t").param("u", userId).param("p", relinkPatientId).param("t", tenantId).update();
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
                        INSERT INTO medical.patients (tenant_id, user_id, patient_code, first_name, last_name, date_of_birth, dob_estimated, sex, phone, email, address_text, blood_type, notes_internal,
                            link_pin_hash, link_pin_expires_at, created_by)
                        VALUES (:t, :u, :c, :fn, :ln, :dob, :est, :sex, :ph, :em, :ad, :bt, :ni, :lh, :le, :cb) RETURNING id
                        """).param("t", tenantId).param("u", userId).param("c", newCode()).param("fn", r.firstName().trim()).param("ln", r.lastName() == null ? "" : r.lastName().trim())
                        .param("dob", dob[0] == null ? null : java.sql.Date.valueOf(dob[0])).param("est", dob[1] != null).param("sex", r.sex()).param("ph", phone).param("em", email)
                        .param("ad", r.addressText()).param("bt", r.bloodType()).param("ni", r.notesInternal())
                        .param("lh", linkPin == null ? null : encoder.encode(linkPin)).param("le", linkPin == null ? null : java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(7))))
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
        if (activationPin != null) out.put("activationPin", activationPin);
        if (linkPin != null) out.put("linkPin", linkPin);
        if ("ASSIGNED".equals(portal)) events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, id, "CLINIC_ADDED"));
        return out;
    }

    /** Staff re-issues the secret a patient needs to get into the portal. Never for someone else's active account. */
    @Transactional
    public Map<String, Object> reissuePin(UUID tenantId, UUID actor, UUID patientId) {
        var p = jdbc.sql("SELECT user_id, phone FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", patientId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found"));
        UUID userId = (UUID) p.get("user_id");
        Map<String, Object> out = new LinkedHashMap<>();
        if (userId != null) {
            User u = users.findById(userId).orElseThrow();
            if (u.getStatus() == User.Status.INVITED) {
                out.put("activationPin", activation.issue(userId, tenantId, actor));
                audit.record(actor, tenantId, "ACTIVATION_PIN_REISSUED", "patient", patientId, null);
                return out;
            }
            throw BusinessException.badRequest("ALREADY_ACTIVE", "This patient already has an active account; they can reset their password themselves or contact support");
        }
        if (p.get("phone") == null) throw BusinessException.badRequest("PHONE_REQUIRED", "Add a phone number first");
        String pin = randomPin();
        jdbc.sql("UPDATE medical.patients SET link_pin_hash = :h, link_pin_expires_at = :e, link_attempts = 0 WHERE id = :p AND tenant_id = :t")
                .param("h", encoder.encode(pin)).param("e", java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(7)))).param("p", patientId).param("t", tenantId).update();
        audit.record(actor, tenantId, "ACTIVATION_PIN_REISSUED", "patient", patientId, null);
        out.put("linkPin", pin);
        return out;
    }

    /** Public on the clinic host: an existing account claims a record with its password + the link PIN. */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse link(UUID tenantId, String identifier, String password, String patientCode, String pin, String ip, String ua) {
        throttle.check("link|" + ip, 10, Duration.ofMinutes(10));
        User user = auth.findByIdentifier(identifier).filter(u -> u.getStatus() == User.Status.ACTIVE && u.getPasswordHash() != null && encoder.matches(password == null ? "" : password, u.getPasswordHash()))
                .orElseThrow(() -> BusinessException.unauthorized("INVALID_CREDENTIALS", "Invalid phone/email or password"));
        var p = jdbc.sql("SELECT id, phone, link_pin_hash, link_pin_expires_at, link_attempts, user_id FROM medical.patients WHERE tenant_id = :t AND patient_code = :c FOR UPDATE")
                .param("t", tenantId).param("c", patientCode == null ? "" : patientCode.trim().toUpperCase()).query().listOfRows().stream().findFirst().orElse(null);
        BusinessException fail = BusinessException.badRequest("LINK_INVALID", "Invalid or expired link code");
        if (p == null || p.get("user_id") != null || p.get("link_pin_hash") == null || ((java.sql.Timestamp) p.get("link_pin_expires_at")).toInstant().isBefore(Instant.now())
                || ((Number) p.get("link_attempts")).intValue() >= MAX_LINK_ATTEMPTS) throw fail;
        if (!encoder.matches(pin == null ? "" : pin, (String) p.get("link_pin_hash"))) {
            jdbc.sql("UPDATE medical.patients SET link_attempts = link_attempts + 1 WHERE id = :p").param("p", p.get("id")).update();
            throw fail;
        }
        if (!user.getPhone().equals(p.get("phone"))) throw fail;   // the record must belong to this phone number
        UUID patientId = (UUID) p.get("id");
        jdbc.sql("UPDATE medical.patients SET user_id = :u, link_pin_hash = NULL, link_pin_expires_at = NULL, updated_at = now() WHERE id = :p").param("u", user.getId()).param("p", patientId).update();
        joinAsPatient(user.getId(), tenantId);
        audit.record(user.getId(), tenantId, "ACCESS_GRANTED", "patient", patientId, null);
        return auth.issueFor(user, ip, ua);
    }

    private void joinAsPatient(UUID userId, UUID tenantId) {
        Membership m = memberships.findByUserIdAndTenantId(userId, tenantId).orElseGet(Membership::new);
        if (m.getId() == null) {
            m.setUserId(userId);
            m.setTenantId(tenantId);
            m = memberships.saveAndFlush(m);
        } else if (m.getStatus() == Membership.Status.REMOVED) {
            m.setStatus(Membership.Status.ACTIVE);   // the person left earlier and the clinic is adding them again
            m = memberships.saveAndFlush(m);
        } else if (m.getStatus() != Membership.Status.ACTIVE) {
            throw BusinessException.forbidden("NOT_A_MEMBER", "This account has no access here");
        }
        rbac.assignTenantRole(m.getId(), "PATIENT");
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
     * The clinic sets a patient's password (e.g. at the front desk). Only allowed while the account belongs to this clinic alone and has no
     * staff role here; accounts shared with other clinics keep their owner's password. All sessions are revoked.
     */
    @Transactional
    public void setPassword(UUID tenantId, UUID actor, UUID patientId, String newPassword) {
        auth.validatePassword(newPassword);
        UUID userId = jdbc.sql("SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", patientId).param("t", tenantId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found"));
        if (userId == null) throw BusinessException.badRequest("PHONE_REQUIRED", "This patient has no portal account yet");
        boolean shared = memberships.findByUserId(userId).stream().anyMatch(x -> !x.getTenantId().equals(tenantId) && x.getStatus() == Membership.Status.ACTIVE);
        Membership m = memberships.findByUserIdAndTenantId(userId, tenantId).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found"));
        boolean staff = rbac.roleCodes(m.getId()).stream().anyMatch(rc -> !Set.of("PATIENT", "GUARDIAN", "CUSTOMER").contains(rc));
        if (shared || staff) throw BusinessException.forbidden("PASSWORD_SET_NOT_ALLOWED", "This account is shared with other clinics; the patient must reset it themselves");
        User u = users.findById(userId).orElseThrow();
        u.setPasswordHash(encoder.encode(newPassword));
        u.setStatus(User.Status.ACTIVE);
        users.save(u);
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update();
        audit.record(actor, tenantId, "PATIENT_PASSWORD_SET", "patient", patientId, null);
    }

    /** A patient leaves a clinic: they lose access to its portal; the clinic keeps its medical record (it must be retained). */
    @Transactional
    public void leave(UUID tenantId, UUID userId) {
        Membership m = memberships.findByUserIdAndTenantId(userId, tenantId).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found"));
        if (rbac.roleCodes(m.getId()).stream().anyMatch(rc -> !Set.of("PATIENT", "GUARDIAN").contains(rc)))
            throw BusinessException.badRequest("CANNOT_LEAVE", "Staff accounts cannot leave this way");
        jdbc.sql("UPDATE medical.patients SET user_id = NULL, updated_at = now() WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", userId).update();
        jdbc.sql("DELETE FROM medical.patient_guardians WHERE tenant_id = :t AND guardian_user_id = :u").param("t", tenantId).param("u", userId).update();
        jdbc.sql("DELETE FROM core.membership_roles WHERE membership_id = :m").param("m", m.getId()).update();
        m.setStatus(Membership.Status.REMOVED);
        memberships.save(m);
        audit.record(userId, tenantId, "ACCESS_REVOKED", "user", userId, "{\"by\":\"patient\"}");
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

    private static String randomPin() { return String.format("%08d", RANDOM.nextInt(100_000_000)); }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}

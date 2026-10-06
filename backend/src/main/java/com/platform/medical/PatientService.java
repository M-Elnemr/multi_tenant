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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                             String bloodType, String notesInternal, UUID guardianPatientId, String relationship) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final ActivationService activation;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final LoginThrottle throttle;
    private final AuditService audit;

    public PatientService(JdbcClient jdbc, UserRepository users, MembershipRepository memberships, RbacService rbac, ActivationService activation,
                          PasswordEncoder encoder, AuthService auth, LoginThrottle throttle, AuditService audit) {
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
        String phone = r.phone() == null || r.phone().isBlank() ? null : PhoneNormalizer.normalize(r.phone());
        String email = r.email() == null || r.email().isBlank() ? null : r.email().trim().toLowerCase();

        UUID guardianUser = null;
        if (r.guardianPatientId() != null) {
            guardianUser = jdbc.sql("SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t").param("p", r.guardianPatientId()).param("t", tenantId).query(UUID.class).optional()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Guardian patient not found"));
            if (guardianUser == null) throw BusinessException.badRequest("GUARDIAN_NOT_LINKED", "The guardian has no portal account yet");
        }

        UUID userId = null;
        String activationPin = null;
        String linkPin = null;
        String portal = "NONE";
        if (phone != null && guardianUser == null) {
            User existing = users.findByPhone(phone).orElse(null);
            if (existing == null) {
                User u = new User();
                u.setPhone(phone);
                u.setEmail(email != null && users.findByEmailIgnoreCase(email).isEmpty() ? email : null);
                u.setFirstName(r.firstName());
                u.setLastName(r.lastName() == null ? "" : r.lastName());
                u.setStatus(User.Status.INVITED);
                u = users.saveAndFlush(u);
                userId = u.getId();
                joinAsPatient(userId, tenantId);
                activationPin = activation.issue(userId, tenantId, actor);
                portal = "ACTIVATION_PIN";
            } else {
                linkPin = randomPin();   // someone already owns this phone: they must claim the record themselves
                portal = "LINK_PIN";
            }
        }

        UUID id = null;
        for (int i = 0; i < 5 && id == null; i++) {
            try {
                id = jdbc.sql("""
                        INSERT INTO medical.patients (tenant_id, user_id, patient_code, first_name, last_name, date_of_birth, sex, phone, email, address_text, blood_type, notes_internal,
                            link_pin_hash, link_pin_expires_at, created_by)
                        VALUES (:t, :u, :c, :fn, :ln, :dob, :sex, :ph, :em, :ad, :bt, :ni, :lh, :le, :cb) RETURNING id
                        """).param("t", tenantId).param("u", userId).param("c", newCode()).param("fn", r.firstName().trim()).param("ln", r.lastName() == null ? "" : r.lastName().trim())
                        .param("dob", r.dateOfBirth() == null ? null : java.sql.Date.valueOf(r.dateOfBirth())).param("sex", r.sex()).param("ph", phone).param("em", email)
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
        return page.wrap(Rows.camel(rows), total);
    }

    Map<String, Object> summary(UUID tenantId, UUID id) {
        return Rows.camel(jdbc.sql("SELECT id, patient_code, first_name, last_name, date_of_birth, sex, phone, email, address_text, blood_type, (user_id IS NOT NULL) AS has_portal, status, created_at FROM medical.patients WHERE id = :p AND tenant_id = :t")
                .param("p", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Patient not found")));
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
        jdbc.sql("""
                UPDATE medical.patients SET first_name = coalesce(:fn, first_name), last_name = coalesce(:ln, last_name), date_of_birth = coalesce(:dob, date_of_birth), sex = coalesce(:sex, sex),
                  email = coalesce(:em, email), address_text = coalesce(:ad, address_text), blood_type = coalesce(:bt, blood_type), notes_internal = coalesce(:ni, notes_internal), updated_at = now()
                WHERE id = :p AND tenant_id = :t
                """).param("fn", r.firstName()).param("ln", r.lastName()).param("dob", r.dateOfBirth() == null ? null : java.sql.Date.valueOf(r.dateOfBirth())).param("sex", r.sex())
                .param("em", r.email()).param("ad", r.addressText()).param("bt", r.bloodType()).param("ni", r.notesInternal()).param("p", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "PATIENT_UPDATED", "patient", id, null);
        return summary(tenantId, id);
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

package com.platform.core.auth;

import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in for the two account kinds that are NOT staff:
 *  - patients (medical.patient_accounts): phone + password, created only by a clinic with a temporary password;
 *  - shop clients (commerce.client_accounts): Google sign-in.
 * Their tokens carry their kind, so a patient token is useless on a store and a client token is useless on a clinic.
 */
@Service
public class AccountAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties jwtProps;
    private final LoginThrottle throttle;
    private final GoogleIdTokenVerifier google;

    public AccountAuthService(JdbcClient jdbc, PasswordEncoder encoder, JwtService jwt, JwtProperties jwtProps, LoginThrottle throttle, GoogleIdTokenVerifier google) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.jwtProps = jwtProps;
        this.throttle = throttle;
        this.google = google;
    }

    public String googleClientId() { return google.clientId(); }

    /** Is this token allowed to act on this tenant's host? Patients only on a clinic they belong to, clients only on a store. */
    public boolean hasAccess(JwtService.Subject s, TenantContext.Current t) {
        if ("PATIENT".equals(s.type())) {
            if (!"CLINIC".equals(t.type())) return false;
            return jdbc.sql("""
                    SELECT count(*) FROM medical.patients p JOIN medical.patient_accounts a ON a.id = p.user_id
                    WHERE p.tenant_id = :t AND p.user_id = :u AND p.status = 'ACTIVE' AND a.status = 'ACTIVE'
                    """).param("t", t.id()).param("u", s.id()).query(Long.class).single() > 0;
        }
        if ("CLIENT".equals(s.type())) {
            if (!"STORE".equals(t.type())) return false;
            return jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE id = :u AND status = 'ACTIVE'").param("u", s.id()).query(Long.class).single() > 0;
        }
        return false;
    }

    // ---- patients ----------------------------------------------------------------------------------------------

    /** Empty when the identifier is not a patient phone or the password is wrong. Throws when the password is right but this clinic has no record of them. */
    @Transactional
    public Optional<TokenResponse> patientLogin(String identifier, String password, TenantContext.Current tenant, String ip, String ua) {
        // A patient signs in on their clinic's address, or on the platform address (the patient app keeps one login for every clinic).
        if ((tenant != null && !"CLINIC".equals(tenant.type())) || identifier == null || PhoneNormalizer.looksLikeEmail(identifier)) return Optional.empty();
        String phone;
        try { phone = PhoneNormalizer.normalize(identifier); } catch (BusinessException e) { return Optional.empty(); }
        var row = jdbc.sql("SELECT id, password_hash, status FROM medical.patient_accounts WHERE phone = :p").param("p", phone).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null || !encoder.matches(password == null ? "" : password, (String) row.get("password_hash"))) return Optional.empty();
        if (!"ACTIVE".equals(row.get("status"))) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
        UUID id = (UUID) row.get("id");
        Long here = tenant == null
                ? jdbc.sql("SELECT count(*) FROM medical.patients WHERE user_id = :u AND status = 'ACTIVE'").param("u", id).query(Long.class).single()
                : jdbc.sql("SELECT count(*) FROM medical.patients WHERE tenant_id = :t AND user_id = :u AND status = 'ACTIVE'").param("t", tenant.id()).param("u", id).query(Long.class).single();
        if (here == 0) throw BusinessException.forbidden("NOT_A_MEMBER", "This account has no access here");
        jdbc.sql("UPDATE medical.patient_accounts SET last_login_at = now() WHERE id = :u").param("u", id).update();
        return Optional.of(issue("PATIENT", id, ip, ua));
    }

    public boolean isPatient(UUID id) { return jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE id = :u").param("u", id).query(Long.class).single() > 0; }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse changePatientPassword(UUID id, String current, String newPassword, String ip, String ua) {
        throttle.check("chgpw|" + id, 5, Duration.ofMinutes(10));
        validatePassword(newPassword);
        String hash = jdbc.sql("SELECT password_hash FROM medical.patient_accounts WHERE id = :u").param("u", id).query(String.class).optional()
                .orElseThrow(() -> BusinessException.unauthorized("UNAUTHENTICATED", "Unknown account"));
        if (!encoder.matches(current == null ? "" : current, hash)) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Current password is wrong");
        if (encoder.matches(newPassword, hash)) throw BusinessException.badRequest("PASSWORD_UNCHANGED", "Choose a password different from the temporary one");
        jdbc.sql("UPDATE medical.patient_accounts SET password_hash = :h, must_change_password = FALSE, updated_at = now() WHERE id = :u").param("h", encoder.encode(newPassword)).param("u", id).update();
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", id).update();
        return issue("PATIENT", id, ip, ua);
    }

    /** Clinic side: find the account for a phone, or create it with the temporary password. Never changes the password of an existing account. */
    public record PatientAccount(UUID id, boolean created) {}

    @Transactional
    public PatientAccount ensurePatientAccount(String phone, String displayName, String tempPassword) {
        var existing = jdbc.sql("SELECT id FROM medical.patient_accounts WHERE phone = :p").param("p", phone).query(UUID.class).optional();
        if (existing.isPresent()) return new PatientAccount(existing.get(), false);
        validatePassword(tempPassword);
        UUID id = jdbc.sql("INSERT INTO medical.patient_accounts (phone, display_name, password_hash, must_change_password) VALUES (:p, :n, :h, TRUE) RETURNING id")
                .param("p", phone).param("n", displayName == null ? "" : displayName).param("h", encoder.encode(tempPassword)).query(UUID.class).single();
        return new PatientAccount(id, true);
    }

    public void validatePassword(String p) {
        if (p == null || p.length() < 8) throw BusinessException.badRequest("WEAK_PASSWORD", "Password must be at least 8 characters");
        if (p.length() > 128) throw BusinessException.badRequest("WEAK_PASSWORD", "Password is too long");
    }

    // ---- shop clients ------------------------------------------------------------------------------------------

    @Transactional
    public TokenResponse clientGoogle(String credential, TenantContext.Current tenant, String ip, String ua) {
        if (tenant == null || !"STORE".equals(tenant.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "Not available here");
        throttle.check("google|" + ip, 20, Duration.ofMinutes(5));
        GoogleIdTokenVerifier.GoogleIdentity g = google.verify(credential);
        var found = jdbc.sql("SELECT id, status FROM commerce.client_accounts WHERE google_sub = :s").param("s", g.sub()).query().listOfRows().stream().findFirst();
        UUID id;
        if (found.isPresent()) {
            if (!"ACTIVE".equals(found.get().get("status"))) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
            id = (UUID) found.get().get("id");
            jdbc.sql("UPDATE commerce.client_accounts SET last_login_at = now(), email = coalesce(:e, email) WHERE id = :i").param("e", g.email()).param("i", id).update();
        } else {
            id = jdbc.sql("INSERT INTO commerce.client_accounts (google_sub, email, name, last_login_at) VALUES (:s, :e, :n, now()) RETURNING id")
                    .param("s", g.sub()).param("e", g.email()).param("n", g.name() == null ? "" : g.name()).query(UUID.class).single();
        }
        return issue("CLIENT", id, ip, ua);
    }

    // ---- shared ------------------------------------------------------------------------------------------------

    @Transactional
    public TokenResponse reissue(String type, UUID id, String ip, String ua) {
        boolean ok = "PATIENT".equals(type)
                ? jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE id = :u AND status = 'ACTIVE'").param("u", id).query(Long.class).single() > 0
                : jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE id = :u AND status = 'ACTIVE'").param("u", id).query(Long.class).single() > 0;
        if (!ok) throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        return issue(type, id, ip, ua);
    }

    public Optional<TokenResponse.UserSummary> me(UUID id) {
        var p = jdbc.sql("SELECT id, display_name, phone, must_change_password FROM medical.patient_accounts WHERE id = :u").param("u", id).query().listOfRows().stream().findFirst();
        if (p.isPresent()) return Optional.of(patientSummary(p.get()));
        return jdbc.sql("SELECT id, name, phone, email FROM commerce.client_accounts WHERE id = :u").param("u", id).query().listOfRows().stream().findFirst().map(this::clientSummary);
    }

    private TokenResponse issue(String type, UUID id, String ip, String ua) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                INSERT INTO core.user_sessions (user_id, principal_type, refresh_token_hash, expires_at, ip_address, user_agent)
                VALUES (:u, :ty, :h, :e, :ip, :ua)
                """).param("u", id).param("ty", type).param("h", sha256(refresh))
                .param("e", java.sql.Timestamp.from(Instant.now().plus(jwtProps.refreshTokenTtl()))).param("ip", ip).param("ua", ua).update();
        TokenResponse.UserSummary summary = me(id).orElseThrow();
        return new TokenResponse(jwt.accessToken(id, type), refresh, jwt.accessTtlSeconds(), summary);
    }

    private TokenResponse.UserSummary patientSummary(java.util.Map<String, Object> r) {
        return new TokenResponse.UserSummary((UUID) r.get("id"), (String) r.get("display_name"), "", (String) r.get("phone"), null, List.of("PATIENT"), List.of(), Boolean.TRUE.equals(r.get("must_change_password")));
    }

    private TokenResponse.UserSummary clientSummary(java.util.Map<String, Object> r) {
        return new TokenResponse.UserSummary((UUID) r.get("id"), (String) r.get("name"), "", (String) r.get("phone"), (String) r.get("email"), List.of("CUSTOMER"), List.of(), false);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

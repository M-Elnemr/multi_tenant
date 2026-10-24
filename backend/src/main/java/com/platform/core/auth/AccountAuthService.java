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
import java.util.Map;
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
    private final PhoneOtpService otp;

    public AccountAuthService(JdbcClient jdbc, PasswordEncoder encoder, JwtService jwt, JwtProperties jwtProps, LoginThrottle throttle, GoogleIdTokenVerifier google, PhoneOtpService otp) {
        this.otp = otp;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.jwtProps = jwtProps;
        this.throttle = throttle;
        this.google = google;
    }

    public String googleClientId() { return google.clientId(); }

    public boolean codesAvailable() { return otp.available(); }

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
        Integer publicId = com.platform.medical.PatientIds.parse(identifier);   // the patient number works as a login name too (a mobile number has 10+ digits)
        if (publicId != null) {
            phone = jdbc.sql("SELECT phone FROM medical.patient_accounts WHERE public_id = :n").param("n", publicId).query(String.class).optional().orElse(null);
            if (phone == null) return Optional.empty();
        } else {
            try { phone = PhoneNormalizer.normalize(identifier); } catch (BusinessException e) { return Optional.empty(); }
        }
        var row = jdbc.sql("SELECT id, password_hash, status FROM medical.patient_accounts WHERE phone = :p").param("p", phone).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null || !encoder.matches(password == null ? "" : password, (String) row.get("password_hash"))) return Optional.empty();
        if (!"ACTIVE".equals(row.get("status"))) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
        requireOpen(tenant);   // right password, but the clinic is switched off: say so
        UUID id = (UUID) row.get("id");
        Long here = tenant == null
                ? jdbc.sql("SELECT count(*) FROM medical.patients WHERE user_id = :u AND status = 'ACTIVE'").param("u", id).query(Long.class).single()
                : jdbc.sql("SELECT count(*) FROM medical.patients WHERE tenant_id = :t AND user_id = :u AND status = 'ACTIVE'").param("t", tenant.id()).param("u", id).query(Long.class).single();
        if (here == 0) throw BusinessException.forbidden("NOT_A_MEMBER", "This account has no access here");
        jdbc.sql("UPDATE medical.patient_accounts SET last_login_at = now() WHERE id = :u").param("u", id).update();
        return Optional.of(issue("PATIENT", id, ip, ua));
    }

    /** A disabled (suspended / cancelled) business is closed to its patients and customers. */
    private static void requireOpen(TenantContext.Current tenant) {
        if (tenant != null && ("SUSPENDED".equals(tenant.status()) || "CANCELLED".equals(tenant.status()) || "ARCHIVED".equals(tenant.status())))
            throw BusinessException.forbidden("TENANT_DISABLED", "This business is currently disabled");
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
        UUID id = jdbc.sql("INSERT INTO medical.patient_accounts (phone, display_name, password_hash, must_change_password, public_id) VALUES (:p, :n, :h, TRUE, :pid) RETURNING id")
                .param("p", phone).param("n", displayName == null ? "" : displayName).param("h", encoder.encode(tempPassword)).param("pid", com.platform.medical.PatientIds.allocate(jdbc)).query(UUID.class).single();
        return new PatientAccount(id, true);
    }

    public void validatePassword(String p) {
        if (p == null || p.length() < 8) throw BusinessException.badRequest("WEAK_PASSWORD", "Password must be at least 8 characters");
        if (p.length() > 128) throw BusinessException.badRequest("WEAK_PASSWORD", "Password is too long");
    }

    // ---- shop clients ------------------------------------------------------------------------------------------

    @Transactional
    public TokenResponse clientGoogle(String credential, TenantContext.Current tenant, String ip, String ua) {
        requireOpen(tenant);
        if (tenant != null && !"STORE".equals(tenant.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "Not available here");   // a shop client signs in on a shop, or once on the platform host for every shop
        UUID id = clientAccountFromGoogle(credential, ip);
        return issue("CLIENT", id, ip, ua);
    }

    /** Verifies a Google credential and returns the shop-client account behind it (created on first sign-in). Shared by the shops and the Open Store. */
    @Transactional
    public UUID clientAccountFromGoogle(String credential, String ip) {
        throttle.check("google|" + ip, 20, Duration.ofMinutes(5));
        GoogleIdTokenVerifier.GoogleIdentity g = google.verify(credential);
        var found = jdbc.sql("SELECT id, status FROM commerce.client_accounts WHERE google_sub = :s").param("s", g.sub()).query().listOfRows().stream().findFirst();
        if (found.isPresent()) {
            if (!"ACTIVE".equals(found.get().get("status"))) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
            UUID id = (UUID) found.get().get("id");
            jdbc.sql("UPDATE commerce.client_accounts SET last_login_at = now(), email = coalesce(:e, email) WHERE id = :i").param("e", g.email()).param("i", id).update();
            return id;
        }
        return jdbc.sql("INSERT INTO commerce.client_accounts (google_sub, email, name, last_login_at) VALUES (:s, :e, :n, now()) RETURNING id")
                .param("s", g.sub()).param("e", g.email()).param("n", g.name() == null ? "" : g.name()).query(UUID.class).single();
    }

    // ---- shop clients with a phone number and password ---------------------------------------------------------------

    private static final String PW_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static String tempPassword() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        return b.toString();
    }

    /** Orders and customer records that were made with this phone before the account existed now belong to the account (in every shop). */
    private void linkByPhone(UUID accountId, String phone) {
        jdbc.sql("UPDATE commerce.customers SET user_id = :a WHERE phone = :p AND user_id IS NULL").param("a", accountId).param("p", phone).update();
        jdbc.sql("UPDATE commerce.orders SET user_id = :a WHERE customer_phone_snapshot = :p AND user_id IS NULL").param("a", accountId).param("p", phone).update();
    }

    public record Ensured(UUID id, boolean created, String tempPassword) {}

    /**
     * A customer confirmed an order on WhatsApp, which proves the phone is theirs: they get an account with a temporary password, or, when the phone already has
     * one, nothing is created (their orders are attached to it). id is null when the account exists but is switched off.
     */
    @Transactional
    public Ensured ensureClientForConfirmedPhone(String phone, String name) {
        var existing = jdbc.sql("SELECT id, status FROM commerce.client_accounts WHERE phone = :p").param("p", phone).query().listOfRows().stream().findFirst().orElse(null);
        if (existing != null) {
            if (!"ACTIVE".equals(existing.get("status"))) return new Ensured(null, false, null);
            UUID id = (UUID) existing.get("id");
            linkByPhone(id, phone);
            return new Ensured(id, false, null);
        }
        String pw = tempPassword();
        UUID id = jdbc.sql("INSERT INTO commerce.client_accounts (name, phone, password_hash, phone_verified_at, must_change_password, created_via) VALUES (:n, :p, :h, now(), TRUE, 'WHATSAPP') RETURNING id")
                .param("n", name == null ? "" : name.trim()).param("p", phone).param("h", encoder.encode(pw)).query(UUID.class).single();
        linkByPhone(id, phone);
        return new Ensured(id, true, pw);
    }

    /** Step 1 of signing up: validate, then send a code on WhatsApp. Nothing is created until the code is entered. */
    public void clientRegister(String name, String phone, String password, String ip) {
        validatePassword(password);
        if (name == null || name.trim().length() < 2) throw BusinessException.badRequest("VALIDATION_ERROR", "Your name is required");
        String p = PhoneNormalizer.normalize(phone);
        if (jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", p).query(Long.class).single() > 0)
            throw BusinessException.conflict("ACCOUNT_EXISTS", "This phone already has an account: sign in, or reset the password");
        otp.request(p, "REGISTER", Map.of("name", name.trim(), "hash", encoder.encode(password)), ip);
    }

    /** Step 2: the code proves the phone; the account is created and signed in. */
    @Transactional(noRollbackFor = BusinessException.class)   // a wrong code must still count as a try
    public TokenResponse clientRegisterVerify(String phone, String code, TenantContext.Current tenant, String ip, String ua) {
        requireOpen(tenant);
        String p = PhoneNormalizer.normalize(phone);
        throttle.check("otp-verify|" + p, 10, Duration.ofMinutes(10));
        var payload = otp.verify(p, "REGISTER", code);
        if (jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", p).query(Long.class).single() > 0)
            throw BusinessException.conflict("ACCOUNT_EXISTS", "This phone already has an account: sign in, or reset the password");
        UUID id = jdbc.sql("INSERT INTO commerce.client_accounts (name, phone, password_hash, phone_verified_at, created_via, last_login_at) VALUES (:n, :p, :h, now(), 'REGISTER', now()) RETURNING id")
                .param("n", String.valueOf(payload.get("name"))).param("p", p).param("h", String.valueOf(payload.get("hash"))).query(UUID.class).single();
        linkByPhone(id, p);
        return issue("CLIENT", id, ip, ua);
    }

    @Transactional
    public TokenResponse clientPhoneLogin(String phone, String password, TenantContext.Current tenant, String ip, String ua) {
        requireOpen(tenant);
        if (tenant != null && !"STORE".equals(tenant.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "Not available here");
        String p;
        try { p = PhoneNormalizer.normalize(phone); } catch (BusinessException e) { throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Wrong phone or password"); }
        throttle.check("client-login|" + p, 8, Duration.ofMinutes(10));
        throttle.check("client-login-ip|" + ip, 30, Duration.ofMinutes(10));
        var row = jdbc.sql("SELECT id, password_hash, status FROM commerce.client_accounts WHERE phone = :p").param("p", p).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null || row.get("password_hash") == null || !encoder.matches(password == null ? "" : password, (String) row.get("password_hash")))
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Wrong phone or password");
        if (!"ACTIVE".equals(row.get("status"))) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
        UUID id = (UUID) row.get("id");
        jdbc.sql("UPDATE commerce.client_accounts SET last_login_at = now() WHERE id = :u").param("u", id).update();
        return issue("CLIENT", id, ip, ua);
    }

    /** "Forgot password": a code on WhatsApp for a phone that has an account. The answer is the same whether or not the phone has one, so nobody can probe for accounts. */
    public void clientForgot(String phone, String ip) {
        String p;
        try { p = PhoneNormalizer.normalize(phone); } catch (BusinessException e) { return; }
        throttle.check("otp-ip|" + ip, 10, Duration.ofMinutes(10));
        if (jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p AND status = 'ACTIVE'").param("p", p).query(Long.class).single() == 0) return;
        otp.request(p, "RESET", null, ip);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse clientReset(String phone, String code, String newPassword, TenantContext.Current tenant, String ip, String ua) {
        requireOpen(tenant);
        validatePassword(newPassword);
        String p = PhoneNormalizer.normalize(phone);
        throttle.check("otp-verify|" + p, 10, Duration.ofMinutes(10));
        otp.verify(p, "RESET", code);
        UUID id = jdbc.sql("SELECT id FROM commerce.client_accounts WHERE phone = :p AND status = 'ACTIVE'").param("p", p).query(UUID.class).optional().orElseThrow(() -> BusinessException.badRequest("INVALID_CODE", "The code is wrong or expired"));
        jdbc.sql("UPDATE commerce.client_accounts SET password_hash = :h, must_change_password = FALSE, phone_verified_at = coalesce(phone_verified_at, now()) WHERE id = :u").param("h", encoder.encode(newPassword)).param("u", id).update();
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", id).update();
        return issue("CLIENT", id, ip, ua);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse changeClientPassword(UUID id, String current, String newPassword, String ip, String ua) {
        throttle.check("chgpw|" + id, 5, Duration.ofMinutes(10));
        validatePassword(newPassword);
        String hash = jdbc.sql("SELECT password_hash FROM commerce.client_accounts WHERE id = :u").param("u", id).query(String.class).optional().orElse(null);
        // an account that signed up with Google has no password yet: it may set one without a current one
        if (hash != null && !encoder.matches(current == null ? "" : current, hash)) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Current password is wrong");
        if (hash != null && encoder.matches(newPassword, hash)) throw BusinessException.badRequest("PASSWORD_UNCHANGED", "Choose a password different from the current one");
        jdbc.sql("UPDATE commerce.client_accounts SET password_hash = :h, must_change_password = FALSE WHERE id = :u").param("h", encoder.encode(newPassword)).param("u", id).update();
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

    /** Handoff to a clinic's own address for a patient: only when this account has an active record in that clinic. */
    public boolean hasPatientRecord(UUID accountId, UUID tenantId) {
        return jdbc.sql("""
                SELECT count(*) FROM medical.patients p JOIN medical.patient_accounts a ON a.id = p.user_id JOIN core.tenants t ON t.id = p.tenant_id
                WHERE p.tenant_id = :t AND p.user_id = :u AND p.status = 'ACTIVE' AND a.status = 'ACTIVE' AND t.status NOT IN ('ARCHIVED','CANCELLED')
                """).param("t", tenantId).param("u", accountId).query(Long.class).single() > 0;
    }

    public TokenResponse issuePatientTokens(UUID accountId, String ip, String ua) { return issue("PATIENT", accountId, ip, ua); }

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

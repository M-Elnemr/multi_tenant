package com.platform.affiliate;

import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Marketer accounts: phone + password, their own opaque session token (an httpOnly cookie on the platform site, sent as X-Marketer-Token on the marketer endpoints only).
 * They have nothing to do with staff, patient or client sign-in, so none of those tokens works here and a marketer token works nowhere else.
 */
@Service
public class MarketerAccounts {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL = Duration.ofDays(30);
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final AffiliateAudit audit;

    public MarketerAccounts(JdbcClient jdbc, PasswordEncoder encoder, LoginThrottle throttle, AffiliateAudit audit) { this.jdbc = jdbc; this.encoder = encoder; this.throttle = throttle; this.audit = audit; }

    public record Session(String token, Map<String, Object> account) {}

    public Session register(String name, String phone, String email, String password, String ip) {
        throttle.check("mkreg|" + (ip == null ? "-" : ip), 10, Duration.ofHours(1));
        boolean open = jdbc.sql("SELECT registration_open FROM affiliate.settings WHERE id = 1").query(Boolean.class).single();
        if (!open) throw new BusinessException(HttpStatus.FORBIDDEN, "affiliate.registrationClosed", "Marketer registration is closed for now");
        if (name == null || name.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.name", "Write your name");
        String p = PhoneNormalizer.normalize(phone);
        if (password == null || password.length() < 8 || password.length() > 128) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.weakPassword", "The password must be 8 to 128 characters");
        if (jdbc.sql("SELECT count(*) FROM affiliate.accounts WHERE phone = :p").param("p", p).query(Long.class).single() > 0) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.phoneTaken", "This number already has a marketer account");
        UUID id = jdbc.sql("INSERT INTO affiliate.accounts (phone, email, name, password_hash) VALUES (:p, :e, :n, :h) RETURNING id")
                .param("p", p).param("e", email == null || email.isBlank() ? null : email.strip()).param("n", name.strip()).param("h", encoder.encode(password)).query(UUID.class).single();
        audit.log("MARKETER", id, null, null, "REGISTER", "New marketer account");
        return new Session(newSession(id), account(id));
    }

    public Session login(String phone, String password, String ip) {
        throttle.check("mklogin|" + (ip == null ? "-" : ip), 30, Duration.ofMinutes(15));
        String p;
        try { p = PhoneNormalizer.normalize(phone); } catch (BusinessException e) { throw bad(); }
        throttle.check("mklogin|" + p, 8, Duration.ofMinutes(15));
        var row = jdbc.sql("SELECT id, password_hash, status FROM affiliate.accounts WHERE phone = :p").param("p", p).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null || !encoder.matches(password == null ? "" : password, (String) row.get("password_hash"))) throw bad();
        if (!"ACTIVE".equals(row.get("status"))) throw new BusinessException(HttpStatus.FORBIDDEN, "affiliate.suspended", "This account is suspended");
        UUID id = (UUID) row.get("id");
        jdbc.sql("UPDATE affiliate.accounts SET last_login_at = now() WHERE id = :i").param("i", id).update();
        return new Session(newSession(id), account(id));
    }

    private static BusinessException bad() { return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Wrong number or password"); }

    /** The account behind a session token, or null (unknown, expired, revoked or suspended). */
    public UUID authenticate(String token) {
        if (token == null || token.isBlank()) return null;
        return jdbc.sql("SELECT s.account_id FROM affiliate.sessions s JOIN affiliate.accounts a ON a.id = s.account_id WHERE s.token_hash = :h AND s.revoked_at IS NULL AND s.expires_at > now() AND a.status = 'ACTIVE'")
                .param("h", sha(token)).query(UUID.class).optional().orElse(null);
    }

    public void logout(String token) { if (token != null) jdbc.sql("UPDATE affiliate.sessions SET revoked_at = now() WHERE token_hash = :h").param("h", sha(token)).update(); }

    public Map<String, Object> account(UUID id) {
        return jdbc.sql("SELECT id, name, phone, email, bio, city, channels, payout_note, status, created_at FROM affiliate.accounts WHERE id = :i").param("i", id).query().listOfRows().stream().findFirst().map(Rows::camel)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Account not found"));
    }

    public Map<String, Object> update(UUID id, String name, String email, String bio, String city, String channelsJson, String payoutNote) {
        if (name != null && name.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.name", "Write your name");
        jdbc.sql("""
                UPDATE affiliate.accounts SET name = coalesce(:n, name), email = coalesce(:e, email), bio = coalesce(:b, bio), city = coalesce(:c, city),
                       channels = coalesce(CAST(:ch AS jsonb), channels), payout_note = coalesce(:pn, payout_note) WHERE id = :i
                """).param("n", name == null ? null : name.strip()).param("e", email).param("b", cut(bio, 600)).param("c", cut(city, 80)).param("ch", channelsJson).param("pn", cut(payoutNote, 300)).param("i", id).update();
        return account(id);
    }

    public void changePassword(UUID id, String current, String next) {
        throttle.check("mkpw|" + id, 5, Duration.ofMinutes(10));
        if (next == null || next.length() < 8 || next.length() > 128) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.weakPassword", "The password must be 8 to 128 characters");
        String hash = jdbc.sql("SELECT password_hash FROM affiliate.accounts WHERE id = :i").param("i", id).query(String.class).single();
        if (!encoder.matches(current == null ? "" : current, hash)) throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The current password is wrong");
        jdbc.sql("UPDATE affiliate.accounts SET password_hash = :h WHERE id = :i").param("h", encoder.encode(next)).param("i", id).update();
        jdbc.sql("UPDATE affiliate.sessions SET revoked_at = now() WHERE account_id = :i AND revoked_at IS NULL").param("i", id).update();
    }

    private String newSession(UUID id) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO affiliate.sessions (token_hash, account_id, expires_at) VALUES (:h, :a, now() + make_interval(secs => :s))").param("h", sha(token)).param("a", id).param("s", (double) TTL.toSeconds()).update();
        return token;
    }

    private static String cut(String s, int n) { return s == null ? null : s.strip().length() > n ? s.strip().substring(0, n) : s.strip(); }

    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

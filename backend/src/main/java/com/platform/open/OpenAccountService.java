package com.platform.open;

import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Marketplace shoppers: phone + password (no SMS or OTP), an opaque bearer token per device, stored hashed. Separate from the shops' own client accounts. */
@Service
public class OpenAccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL = Duration.ofDays(365);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;

    public OpenAccountService(JdbcClient jdbc, PasswordEncoder encoder, LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.throttle = throttle;
    }

    @Transactional
    public Map<String, Object> register(String name, String phone, String password, String ip) {
        throttle.check("open-register|" + ip, 10, Duration.ofHours(1));
        String p = requirePhone(phone);
        requirePassword(password);
        if (name == null || name.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Your name is required");
        UUID id;
        try {
            id = jdbc.sql("INSERT INTO open.accounts (phone, name, password_hash, last_login_at) VALUES (:p, :n, :h, now()) RETURNING id")
                    .param("p", p).param("n", name.trim()).param("h", encoder.encode(password)).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("PHONE_TAKEN", "This number already has an account. Sign in instead.");
        }
        return session(id);
    }

    @Transactional
    public Map<String, Object> login(String phone, String password, String ip) {
        String p = requirePhone(phone);
        throttle.check("open-login|" + ip + "|" + p, 10, Duration.ofMinutes(10));
        var acc = jdbc.sql("SELECT id, password_hash, status FROM open.accounts WHERE phone = :p").param("p", p).query().listOfRows().stream().findFirst().orElse(null);
        // one answer for "no such account" and "wrong password", and the hash is always checked, so neither is distinguishable
        boolean ok = encoder.matches(password == null ? "" : password, acc == null ? encoder.encode("x") : (String) acc.get("password_hash"));
        if (acc == null || !ok || !"ACTIVE".equals(acc.get("status"))) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Wrong number or password");
        jdbc.sql("UPDATE open.accounts SET last_login_at = now() WHERE id = :i").param("i", acc.get("id")).update();
        return session((UUID) acc.get("id"));
    }

    private Map<String, Object> session(UUID accountId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO open.sessions (account_id, token_hash, expires_at) VALUES (:a, :h, now() + CAST(:ttl AS interval))")
                .param("a", accountId).param("h", hash(token)).param("ttl", TTL.toDays() + " days").update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("account", profile(accountId));
        return out;
    }

    /** The account behind an X-Open-Token header value, or null when it is missing, unknown or expired. */
    public UUID accountOf(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return null;
        return jdbc.sql("""
                SELECT s.account_id FROM open.sessions s JOIN open.accounts a ON a.id = s.account_id
                WHERE s.token_hash = :h AND s.expires_at > now() AND a.status = 'ACTIVE'
                """).param("h", hash(token)).query(UUID.class).optional().orElse(null);
    }

    public UUID require(String token) {
        UUID id = accountOf(token);
        if (id == null) throw BusinessException.unauthorized("UNAUTHENTICATED", "Please sign in");
        return id;
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) jdbc.sql("DELETE FROM open.sessions WHERE token_hash = :h").param("h", hash(token)).update();
    }

    public Map<String, Object> profile(UUID id) {
        var r = jdbc.sql("SELECT id, name, phone, created_at FROM open.accounts WHERE id = :i").param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found"));
        return com.platform.shared.Rows.camel(r);
    }

    @Transactional
    public Map<String, Object> update(UUID id, String name) {
        if (name != null && !name.isBlank()) jdbc.sql("UPDATE open.accounts SET name = :n WHERE id = :i").param("n", name.trim()).param("i", id).update();
        return profile(id);
    }

    @Transactional
    public void changePassword(UUID id, String current, String next) {
        requirePassword(next);
        String hash = jdbc.sql("SELECT password_hash FROM open.accounts WHERE id = :i").param("i", id).query(String.class).single();
        if (!encoder.matches(current == null ? "" : current, hash)) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Wrong current password");
        jdbc.sql("UPDATE open.accounts SET password_hash = :h WHERE id = :i").param("h", encoder.encode(next)).param("i", id).update();
    }

    /** Deletes the login and personal details; the orders stay with their shops, which hold their own name/phone snapshot. */
    @Transactional
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM open.accounts WHERE id = :i").param("i", id).update();   // sessions go with it; order groups keep their phone for the shops' records
    }

    private static String requirePhone(String phone) {
        String p = PhoneNormalizer.normalize(phone);
        if (p == null) throw BusinessException.badRequest("INVALID_PHONE", "Invalid phone number");
        return p;
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 100) throw BusinessException.badRequest("WEAK_PASSWORD", "The password must be at least 8 characters");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

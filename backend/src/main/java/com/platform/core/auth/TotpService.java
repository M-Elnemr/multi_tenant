package com.platform.core.auth;

import com.platform.chat.TokenCrypto;
import com.platform.shared.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Authenticator-app codes (RFC 6238: 6 digits, 30 seconds, SHA-1, one step either side for clock drift) as a second step of staff sign-in. A code works once, wrong codes are
 * throttled per account, and eight one-time recovery codes are given when it is switched on so a lost phone does not lock the owner out.
 */
@Service
public class TotpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final TokenCrypto crypto;
    private final LoginThrottle throttle;

    public TotpService(JdbcClient jdbc, TokenCrypto crypto, LoginThrottle throttle) { this.jdbc = jdbc; this.crypto = crypto; this.throttle = throttle; }

    public boolean enabled(UUID user) {
        return jdbc.sql("SELECT enabled FROM core.user_totp WHERE user_id = :u").param("u", user).query(Boolean.class).optional().orElse(false);
    }

    /** Starts (or restarts, while it is not yet on) the set-up: a fresh secret to put in the authenticator app. */
    public Map<String, Object> setup(UUID user) {
        if (enabled(user)) throw BusinessException.conflict("MFA_ALREADY_ON", "Two-step sign-in is already on");
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        String secret = base32(raw);
        jdbc.sql("""
                INSERT INTO core.user_totp (user_id, secret_enc) VALUES (:u, :s)
                ON CONFLICT (user_id) DO UPDATE SET secret_enc = :s, enabled = FALSE, last_step = 0, recovery_hashes = '{}', created_at = now(), enabled_at = NULL
                """).param("u", user).param("s", crypto.encrypt(secret)).update();
        String label = jdbc.sql("SELECT coalesce(email, phone, id::text) FROM core.users WHERE id = :u").param("u", user).query(String.class).single();
        String issuer = "Elmanassa";
        return Map.of("secret", secret, "otpauthUri", "otpauth://totp/" + enc(issuer + ":" + label) + "?secret=" + secret + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits=6&period=30");
    }

    /** Checks the first code from the app, switches the feature on and returns the recovery codes (shown once). */
    public List<String> enable(UUID user, String code) {
        throttle.check("mfa-enable|" + user, 10, Duration.ofMinutes(10));
        var row = jdbc.sql("SELECT secret_enc, enabled FROM core.user_totp WHERE user_id = :u").param("u", user).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.badRequest("MFA_NOT_STARTED", "Start the set-up first"));
        if (Boolean.TRUE.equals(row.get("enabled"))) throw BusinessException.conflict("MFA_ALREADY_ON", "Two-step sign-in is already on");
        if (!acceptCode(user, crypto.decrypt((String) row.get("secret_enc")), code)) throw BusinessException.badRequest("INVALID_MFA_CODE", "That code is not right. Check the time on your phone and try again");
        List<String> recovery = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 8; i++) { String c = recoveryCode(); recovery.add(c); hashes.add(hash(c)); }
        jdbc.sql("UPDATE core.user_totp SET enabled = TRUE, enabled_at = now(), recovery_hashes = CAST(:h AS text[]) WHERE user_id = :u")
                .param("h", "{" + String.join(",", hashes) + "}").param("u", user).update();
        return recovery;
    }

    /** Sign-in step: nothing to do when it is off; otherwise a current code (or an unused recovery code) is required. */
    public void requireAtLogin(UUID user, String code) {
        var row = jdbc.sql("SELECT secret_enc FROM core.user_totp WHERE user_id = :u AND enabled").param("u", user).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) return;
        if (code == null || code.isBlank()) throw BusinessException.unauthorized("MFA_REQUIRED", "Enter the 6-digit code from your authenticator app");
        throttle.check("mfa|" + user, 5, Duration.ofMinutes(10));
        if (acceptCode(user, crypto.decrypt((String) row.get("secret_enc")), code) || useRecovery(user, code)) { throttle.reset("mfa|" + user); return; }
        throw BusinessException.unauthorized("INVALID_MFA_CODE", "That code is not right");
    }

    /** Switching it off needs a valid code (or recovery code) too, so a stolen session cannot remove the protection. */
    public void disable(UUID user, String code) {
        requireAtLogin(user, code == null || code.isBlank() ? null : code);
        jdbc.sql("DELETE FROM core.user_totp WHERE user_id = :u").param("u", user).update();
    }

    // ---- codes -----------------------------------------------------------------------------------------------------------------

    private boolean acceptCode(UUID user, String secret, String code) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return false;
        long now = Instant.now().getEpochSecond() / 30;
        for (long step = now - 1; step <= now + 1; step++) {
            if (!MessageDigest.isEqual(otp(secret, step).getBytes(StandardCharsets.UTF_8), digits.getBytes(StandardCharsets.UTF_8))) continue;
            // a code is good once: the step must be newer than the last one accepted
            return jdbc.sql("UPDATE core.user_totp SET last_step = :s WHERE user_id = :u AND last_step < :s").param("s", step).param("u", user).update() == 1;
        }
        return false;
    }

    private boolean useRecovery(UUID user, String code) {
        String c = code.replaceAll("[\\s-]", "").toUpperCase();
        if (c.length() != 10) return false;
        return jdbc.sql("UPDATE core.user_totp SET recovery_hashes = array_remove(recovery_hashes, :h) WHERE user_id = :u AND :h = ANY(recovery_hashes)").param("h", hash(c)).param("u", user).update() == 1;
    }

    public static String otp(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(fromBase32(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String recoveryCode() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        return b.toString();
    }

    private static String hash(String s) { return AuthService.sha256(s); }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    static String base32(byte[] in) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : in) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] fromBase32(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : s.toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}

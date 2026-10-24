package com.platform.core.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.PlatformWhatsApp;
import com.platform.shared.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One-time codes sent on WhatsApp (free) from the platform's own number, so a phone number can only be registered or reset by the person who has it.
 * A code is six digits, lasts ten minutes, works once, and is locked after five wrong tries. Three codes an hour per number, and a limit per address.
 */
@Service
public class PhoneOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MINUTES = 10, MAX_TRIES = 5, PER_HOUR = 3;

    private final JdbcClient jdbc;
    private final PlatformWhatsApp whatsapp;
    private final LoginThrottle throttle;

    public PhoneOtpService(JdbcClient jdbc, PlatformWhatsApp whatsapp, LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.whatsapp = whatsapp;
        this.throttle = throttle;
    }

    private static String hash(String phone, String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean available() { return whatsapp.connected(); }

    /** Sends a new code for {@code phone} (E.164, already normalized). {@code payload} is kept with the code and handed back when it is verified. */
    public void request(String phone, String purpose, Map<String, Object> payload, String ip) {
        throttle.check("otp-ip|" + ip, 10, Duration.ofMinutes(10));
        if (!whatsapp.connected()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OTP_NOT_AVAILABLE", "Phone verification is not available right now");
        if (jdbc.sql("SELECT count(*) FROM core.phone_otps WHERE phone = :p AND created_at > now() - interval '1 hour'").param("p", phone).query(Long.class).single() >= PER_HOUR)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many codes were requested for this number, try again later");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String json;
        try { json = payload == null ? null : JSON.writeValueAsString(payload); } catch (Exception e) { throw new IllegalStateException(e); }
        UUID id = jdbc.sql("INSERT INTO core.phone_otps (phone, purpose, code_hash, payload, expires_at) VALUES (:p, :pu, :h, CAST(:pl AS jsonb), now() + make_interval(mins => :m)) RETURNING id")
                .param("p", phone).param("pu", purpose).param("h", hash(phone, code)).param("pl", json).param("m", MINUTES).query(UUID.class).single();
        String text = "رمز التحقق الخاص بك: " + code + "\nصالح لمدة " + MINUTES + " دقائق. لا تشاركه مع أحد.\nYour verification code: " + code + " (valid " + MINUTES + " minutes, do not share it).";
        if (!whatsapp.send(phone, text)) {
            jdbc.sql("DELETE FROM core.phone_otps WHERE id = :i").param("i", id).update();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OTP_NOT_AVAILABLE", "The code could not be sent on WhatsApp, try again in a moment");
        }
    }

    /** Checks the latest unused code of this purpose. Returns the payload stored with it. Wrong, expired, used or locked codes are all "invalid code". */
    @org.springframework.transaction.annotation.Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> verify(String phone, String purpose, String code) {
        var row = jdbc.sql("SELECT id, code_hash, attempts, payload::text AS payload FROM core.phone_otps WHERE phone = :p AND purpose = :pu AND used_at IS NULL AND expires_at > now() ORDER BY created_at DESC LIMIT 1 FOR UPDATE")
                .param("p", phone).param("pu", purpose).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) throw BusinessException.badRequest("INVALID_CODE", "The code is wrong or expired");
        UUID id = (UUID) row.get("id");
        if (((Number) row.get("attempts")).intValue() >= MAX_TRIES) throw BusinessException.badRequest("INVALID_CODE", "The code is wrong or expired");
        String given = code == null ? "" : code.replaceAll("\\D", "");
        if (!MessageDigest.isEqual(row.get("code_hash").toString().getBytes(StandardCharsets.UTF_8), hash(phone, given).getBytes(StandardCharsets.UTF_8))) {
            jdbc.sql("UPDATE core.phone_otps SET attempts = attempts + 1 WHERE id = :i").param("i", id).update();
            throw BusinessException.badRequest("INVALID_CODE", "The code is wrong or expired");
        }
        jdbc.sql("UPDATE core.phone_otps SET used_at = now() WHERE id = :i").param("i", id).update();
        try { return row.get("payload") == null ? Map.of() : JSON.readValue(row.get("payload").toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); } catch (Exception e) { return Map.of(); }
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 20 3 * * *")
    public void purge() { jdbc.sql("DELETE FROM core.phone_otps WHERE created_at < now() - interval '2 days'").update(); }
}

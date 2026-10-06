package com.platform.core.auth;

import com.platform.core.user.User;
import com.platform.shared.BusinessException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time activation PINs replace SMS OTP: the clinic/store hands the PIN to the person (in person,
 * printed, or via their own WhatsApp) and the person uses phone + PIN to create their first password.
 */
@Service
public class ActivationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthProperties props;

    public ActivationService(JdbcClient jdbc, PasswordEncoder encoder, AuthProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    /** Issues a new PIN (invalidating older ones) and returns it in clear text exactly once. */
    @Transactional
    public String issue(UUID userId, UUID tenantId, UUID createdBy) {
        jdbc.sql("UPDATE core.activation_pins SET used_at = now() WHERE user_id = :u AND used_at IS NULL")
                .param("u", userId).update();
        String pin = String.format("%08d", RANDOM.nextInt(100_000_000));
        jdbc.sql("""
                INSERT INTO core.activation_pins (user_id, tenant_id, pin_hash, expires_at, created_by)
                VALUES (:u, :t, :h, :e, :c)
                """)
                .param("u", userId).param("t", tenantId).param("h", encoder.encode(pin))
                .param("e", java.sql.Timestamp.from(Instant.now().plus(props.activationPinTtl())))
                .param("c", createdBy).update();
        return pin;
    }

    /** Verifies and consumes the PIN. Wrong guesses are counted; too many burns the PIN. */
    @Transactional(noRollbackFor = BusinessException.class)
    public void consume(User user, String pin) {
        var row = jdbc.sql("""
                SELECT id, pin_hash, attempts FROM core.activation_pins
                WHERE user_id = :u AND used_at IS NULL AND expires_at > now()
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """).param("u", user.getId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.badRequest("ACTIVATION_INVALID", "Invalid or expired activation code"));
        UUID id = (UUID) row.get("id");
        int attempts = ((Number) row.get("attempts")).intValue();
        if (!encoder.matches(pin, (String) row.get("pin_hash"))) {
            attempts++;
            jdbc.sql("UPDATE core.activation_pins SET attempts = :a, used_at = CASE WHEN :a >= :max THEN now() ELSE NULL END WHERE id = :id")
                    .param("a", attempts).param("max", props.maxActivationAttempts()).param("id", id).update();
            throw BusinessException.badRequest("ACTIVATION_INVALID", "Invalid or expired activation code");
        }
        jdbc.sql("UPDATE core.activation_pins SET used_at = now() WHERE id = :id").param("id", id).update();
    }
}

package com.platform.core.auth;

import com.platform.audit.AuditService;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
import com.platform.shared.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One login for every place a person belongs to. Browsers keep a separate session per host, so signing in on the platform host cannot
 * reach a clinic's host by itself. Instead the platform host asks for a ticket for ONE tenant (requires an active membership there) and the
 * browser carries it to that tenant's host, which swaps it for a normal session. A ticket works once, for 60 seconds, only on its own
 * tenant's host, and only its hash is stored.
 */
@Service
public class SsoService {
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuthService auth;
    private final UserRepository users;
    private final LoginThrottle throttle;
    private final AuditService audit;

    public SsoService(JdbcClient jdbc, AuthService auth, UserRepository users, LoginThrottle throttle, AuditService audit) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.users = users;
        this.throttle = throttle;
        this.audit = audit;
    }

    @Transactional
    public String createTicket(UUID userId, UUID tenantId) {
        Long member = jdbc.sql("""
                SELECT count(*) FROM core.user_tenant_memberships m JOIN core.tenants t ON t.id = m.tenant_id
                WHERE m.user_id = :u AND m.tenant_id = :t AND m.status = 'ACTIVE' AND t.status NOT IN ('ARCHIVED','CANCELLED')
                """).param("u", userId).param("t", tenantId).query(Long.class).single();
        if (member == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");   // same answer for "no such place" and "not yours"
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO core.sso_tickets (token_hash, user_id, tenant_id, expires_at) VALUES (:h, :u, :t, now() + (:s || ' seconds')::interval)")
                .param("h", hash(ticket)).param("u", userId).param("t", tenantId).param("s", String.valueOf(TTL.toSeconds())).update();
        return ticket;
    }

    /** Called on the tenant's own host. Marks the ticket used in the same statement that finds it, so two requests can never both win. */
    @Transactional
    public TokenResponse redeem(String ticket, UUID tenantId, String ip, String userAgent) {
        throttle.check("sso|" + ip, 30, Duration.ofMinutes(1));
        UUID userId = ticket == null || ticket.length() < 20 || ticket.length() > 100 ? null : jdbc.sql("""
                UPDATE core.sso_tickets SET used_at = now()
                WHERE token_hash = :h AND tenant_id = :t AND used_at IS NULL AND expires_at > now() RETURNING user_id
                """).param("h", hash(ticket)).param("t", tenantId).query(UUID.class).optional().orElse(null);
        if (userId == null) throw BusinessException.unauthorized("INVALID_TICKET", "This sign-in link is invalid or expired");
        User user = users.findById(userId).filter(u -> u.getStatus() == User.Status.ACTIVE).orElseThrow(() -> BusinessException.unauthorized("INVALID_TICKET", "This sign-in link is invalid or expired"));
        audit.record(userId, tenantId, "SSO_HANDOFF", "user", userId, null);
        return auth.issueFor(user, ip, userAgent);
    }

    private static String hash(String ticket) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.platform.core.auth;

import com.platform.core.rbac.RbacService;
import com.platform.core.user.Membership;
import com.platform.core.user.MembershipRepository;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
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

@Service
public class AuthService {
    public enum IdentifierState { ENTER_PASSWORD, ACTIVATE }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH_SOURCE = "dummy-password-for-timing";

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties jwtProps;
    private final ActivationService activation;
    private final LoginThrottle throttle;
    private final JdbcClient jdbc;
    private final String dummyHash;
    private final List<TenantAutoJoin> autoJoins;

    public AuthService(UserRepository users, MembershipRepository memberships, RbacService rbac, PasswordEncoder encoder,
                       JwtService jwt, JwtProperties jwtProps, ActivationService activation, LoginThrottle throttle,
                       JdbcClient jdbc, List<TenantAutoJoin> autoJoins) {
        this.autoJoins = autoJoins;
        this.users = users;
        this.memberships = memberships;
        this.rbac = rbac;
        this.encoder = encoder;
        this.jwt = jwt;
        this.jwtProps = jwtProps;
        this.activation = activation;
        this.throttle = throttle;
        this.jdbc = jdbc;
        this.dummyHash = encoder.encode(DUMMY_HASH_SOURCE);
    }

    public Optional<User> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String id = identifier.trim();
        try {
            return PhoneNormalizer.looksLikeEmail(id) ? users.findByEmailIgnoreCase(id) : users.findByPhone(PhoneNormalizer.normalize(id));
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    /**
     * Tells the login screen whether to ask for a password or start first-time activation. Unknown
     * identifiers answer ENTER_PASSWORD so the endpoint does not reveal which phones are registered.
     */
    public IdentifierState checkIdentifier(String identifier, String ip) {
        throttle.check("check|" + ip, 30, Duration.ofMinutes(1));
        return findByIdentifier(identifier)
                .filter(u -> u.getStatus() == User.Status.INVITED && u.getPasswordHash() == null)
                .map(u -> IdentifierState.ACTIVATE).orElse(IdentifierState.ENTER_PASSWORD);
    }

    @Transactional
    public TokenResponse login(String identifier, String password, String ip, String userAgent) {
        String key = "login|" + ip + "|" + (identifier == null ? "" : identifier.toLowerCase());
        throttle.check(key, 5, Duration.ofMinutes(1));
        Optional<User> found = findByIdentifier(identifier);
        String hash = found.map(User::getPasswordHash).orElse(null);
        boolean ok = encoder.matches(password == null ? "" : password, hash != null ? hash : dummyHash) && hash != null;
        if (!ok || found.isEmpty()) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Invalid phone/email or password");
        User user = found.get();
        if (user.getStatus() != User.Status.ACTIVE) throw BusinessException.forbidden("ACCOUNT_DISABLED", "Account is not active");
        requireTenantAccess(user);
        throttle.reset(key);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return issueTokens(user, ip, userAgent);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse activate(String identifier, String pin, String newPassword, String ip, String userAgent) {
        throttle.check("activate|" + ip + "|" + (identifier == null ? "" : identifier.toLowerCase()), 10, Duration.ofMinutes(10));
        validatePassword(newPassword);
        User user = findByIdentifier(identifier)
                .filter(u -> u.getStatus() == User.Status.INVITED)
                .orElseThrow(() -> BusinessException.badRequest("ACTIVATION_INVALID", "Invalid or expired activation code"));
        activation.consume(user, pin);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setStatus(User.Status.ACTIVE);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        requireTenantAccess(user);
        return issueTokens(user, ip, userAgent);
    }

    /** Resets a forgotten password using a PIN reissued by clinic/store staff (see StaffService). */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse resetPassword(String identifier, String pin, String newPassword, String ip, String userAgent) {
        throttle.check("reset|" + ip + "|" + (identifier == null ? "" : identifier.toLowerCase()), 10, Duration.ofMinutes(10));
        validatePassword(newPassword);
        User user = findByIdentifier(identifier)
                .orElseThrow(() -> BusinessException.badRequest("ACTIVATION_INVALID", "Invalid or expired activation code"));
        activation.consume(user, pin);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setStatus(User.Status.ACTIVE);
        users.save(user);
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", user.getId()).update();
        requireTenantAccess(user);
        return issueTokens(user, ip, userAgent);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(String refreshToken, String ip, String userAgent) {
        if (refreshToken == null || refreshToken.isBlank()) throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        var row = jdbc.sql("SELECT id, user_id, expires_at, revoked_at FROM core.user_sessions WHERE refresh_token_hash = :h FOR UPDATE")
                .param("h", sha256(refreshToken)).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token"));
        UUID userId = (UUID) row.get("user_id");
        UUID sessionId = (UUID) row.get("id");
        if (row.get("revoked_at") != null) {
            // A rotated-out token was presented again: assume theft and kill every session of the user.
            jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update();
            throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        }
        if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now()))
            throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        User user = users.findById(userId).filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token"));
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE id = :id").param("id", sessionId).update();
        return issueTokens(user, ip, userAgent);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) return;
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE refresh_token_hash = :h AND revoked_at IS NULL")
                .param("h", sha256(refreshToken)).update();
    }

    public TokenResponse.UserSummary me(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> BusinessException.unauthorized("UNAUTHENTICATED", "Unknown user"));
        return summary(u);
    }

    public void validatePassword(String p) {
        if (p == null || p.length() < 8) throw BusinessException.badRequest("WEAK_PASSWORD", "Password must be at least 8 characters");
        if (p.length() > 128) throw BusinessException.badRequest("WEAK_PASSWORD", "Password is too long");
    }

    // -------------------------------------------------------------------------------------------

    /** On a tenant host the user must belong to that tenant; on the platform host any account may sign in. */
    private void requireTenantAccess(User user) {
        TenantContext.Current t = TenantContext.get();
        if (t == null) return;
        boolean member = memberships.findByUserIdAndTenantId(user.getId(), t.id())
                .filter(m -> m.getStatus() == Membership.Status.ACTIVE).isPresent();
        if (!member) {
            // Open-registration tenants (stores) let any valid account become a customer on first login.
            var join = autoJoins.stream().filter(j -> j.supports(t.type())).findFirst();
            if (join.isEmpty()) throw BusinessException.forbidden("NOT_A_MEMBER", "This account has no access here");
            join.get().join(user.getId(), t.id());
        }
    }

    /** Issues tokens for an account that was just created/verified by another flow (e.g. shopper registration). */
    public TokenResponse issueFor(User user, String ip, String userAgent) { return issueTokens(user, ip, userAgent); }

    private TokenResponse issueTokens(User user, String ip, String userAgent) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                INSERT INTO core.user_sessions (user_id, refresh_token_hash, expires_at, ip_address, user_agent)
                VALUES (:u, :h, :e, :ip, :ua)
                """)
                .param("u", user.getId()).param("h", sha256(refresh))
                .param("e", java.sql.Timestamp.from(Instant.now().plus(jwtProps.refreshTokenTtl())))
                .param("ip", ip).param("ua", userAgent).update();
        return new TokenResponse(jwt.accessToken(user.getId()), refresh, jwt.accessTtlSeconds(), summary(user));
    }

    private TokenResponse.UserSummary summary(User u) {
        List<String> roles = List.of();
        TenantContext.Current t = TenantContext.get();
        if (t != null) {
            roles = memberships.findByUserIdAndTenantId(u.getId(), t.id())
                    .map(m -> rbac.roleCodes(m.getId())).orElse(List.of());
        }
        return new TokenResponse.UserSummary(u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(), u.getEmail(), roles);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

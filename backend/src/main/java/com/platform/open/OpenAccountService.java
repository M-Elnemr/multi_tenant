package com.platform.open;

import com.platform.commerce.ShopperService;
import com.platform.core.auth.AccountAuthService;
import com.platform.shared.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marketplace shoppers are the shops' own clients (commerce.client_accounts, signed in with Google): the same person is one account in every shop
 * and in the Open Store. The marketplace keeps its own long-lived opaque token per device (stored hashed), so the website and the app stay signed in.
 */
@Service
public class OpenAccountService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AccountAuthService auth;
    private final ShopperService shopper;
    private final com.platform.shared.PlatformProperties props;

    public OpenAccountService(JdbcClient jdbc, AccountAuthService auth, ShopperService shopper, com.platform.shared.PlatformProperties props) {
        this.props = props;
        this.jdbc = jdbc;
        this.auth = auth;
        this.shopper = shopper;
    }

    public Map<String, Object> config() {
        String id = auth.googleClientId();
        return id == null ? Map.of("googleEnabled", false) : Map.of("googleEnabled", true, "googleClientId", id, "signInHost", props.rootDomain());   // Google only trusts the platform address; the marketplace signs in through it, like every shop
    }

    /** Signs in (or signs up) with a Google credential and returns the marketplace token plus the account. */
    @Transactional
    public Map<String, Object> googleLogin(String credential, String ip) {
        UUID id = auth.clientAccountFromGoogle(credential, ip);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO open.sessions (account_id, token_hash, expires_at) VALUES (:a, :h, now() + interval '365 days')").param("a", id).param("h", hash(token)).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("account", shopper.profile(id));
        return out;
    }

    /** The account behind an X-Open-Token header value, or null when it is missing, unknown, expired or the account is no longer active. */
    public UUID accountOf(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return null;
        return jdbc.sql("""
                SELECT s.account_id FROM open.sessions s JOIN commerce.client_accounts a ON a.id = s.account_id
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

    public Map<String, Object> profile(UUID id) { return shopper.profile(id); }

    /** Name and phone live on the client account, so a number added at checkout or here is the same one every shop sees. */
    public Map<String, Object> update(UUID id, String name, String phone) { return shopper.updateProfile(id, name, phone); }

    /** Deletes the login and personal details (same as in a shop); orders stay with the shops. */
    @Transactional
    public void delete(UUID id) {
        shopper.deleteAccount(id);
        jdbc.sql("DELETE FROM open.sessions WHERE account_id = :a").param("a", id).update();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

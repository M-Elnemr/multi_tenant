package com.platform.integrations;

import com.platform.shared.BusinessException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Log in with Salla / YouCan / Amazon": the owner is sent to the platform, comes back to one fixed address on the platform host, and the account is stored.
 * The shop and the person travel in a signed, short-lived {@code state}, so the redirect address can stay fixed (the platforms need it registered).
 */
@Service
public class IntegrationOAuthService {
    private static final Logger log = LoggerFactory.getLogger(IntegrationOAuthService.class);

    private final OAuthApps apps;
    private final IntegrationService integrations;
    private final HttpGateway http;
    private final JdbcClient jdbc;
    private final String secret;
    private final boolean amazonBeta;

    public IntegrationOAuthService(OAuthApps apps, IntegrationService integrations, HttpGateway http, JdbcClient jdbc, @Value("${app.jwt.secret}") String jwtSecret, @Value("${app.integrations.amazon.beta:true}") boolean amazonBeta) {
        this.apps = apps;
        this.integrations = integrations;
        this.http = http;
        this.jdbc = jdbc;
        this.secret = "integration-oauth|" + jwtSecret;
        this.amazonBeta = amazonBeta;
    }

    public String startUrl(UUID tenantId, UUID userId, String provider, String country) {
        String p = provider.toUpperCase();
        Providers.Def d = Providers.get(p);
        OAuthApps.App app = apps.of(p);
        if (d == null || !d.oauth() || !app.ok()) throw BusinessException.badRequest("OAUTH_NOT_CONFIGURED", "Logging in with " + (d == null ? provider : d.name()) + " is not set up on the server yet");
        String c = country == null ? "EG" : country.toUpperCase();
        if (!d.countries().contains(c)) throw BusinessException.badRequest("COUNTRY_NOT_SUPPORTED", d.name() + " is not available for this country");
        String state = sign(tenantId + "." + userId + "." + p + "." + c + "." + (Instant.now().getEpochSecond() + 900));
        String redirect = enc(apps.redirectUri(p));
        return switch (p) {
            case "SALLA" -> "https://accounts.salla.sa/oauth2/auth?client_id=" + enc(app.clientId()) + "&response_type=code&scope=offline_access&redirect_uri=" + redirect + "&state=" + enc(state);
            case "YOUCAN" -> "https://seller-area.youcan.shop/admin/oauth/authorize?client_id=" + enc(app.clientId()) + "&response_type=code&redirect_uri=" + redirect + "&state=" + enc(state);
            case "AMAZON" -> "https://sellercentral.amazon." + c.toLowerCase() + "/apps/authorize/consent?application_id=" + enc(app.extra()) + "&state=" + enc(state) + (amazonBeta ? "&version=beta" : "");
            default -> throw BusinessException.badRequest("OAUTH_NOT_CONFIGURED", "Not available");
        };
    }

    /** Returns where to send the browser next: the dashboard, with the outcome. */
    public String callback(String provider, Map<String, String> params) {
        String p = provider.toUpperCase();
        String[] st = verify(params.get("state"));
        if (st == null || !st[2].equals(p)) throw BusinessException.badRequest("INVALID_STATE", "This connection link has expired, please start again");
        UUID tenantId = UUID.fromString(st[0]), userId = UUID.fromString(st[1]);
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Shop not found"));
        String back = "https://" + host + "/dashboard/channels";
        String code = params.getOrDefault("code", params.get("spapi_oauth_code"));
        if (code == null || code.isBlank() || params.containsKey("error")) return back + "?error=denied";
        try {
            OAuthApps.App app = apps.of(p);
            Map<String, String> cred = new LinkedHashMap<>();
            switch (p) {
                case "SALLA" -> {
                    HttpGateway.Res r = http.call(HttpGateway.Req.form("https://accounts.salla.sa/oauth2/token", Map.of("Accept", "application/json"),
                            Map.of("grant_type", "authorization_code", "client_id", app.clientId(), "client_secret", app.clientSecret(), "redirect_uri", apps.redirectUri(p), "code", code), "salla-oauth"));
                    cred.put("accessToken", r.json().path("access_token").asText());
                    if (r.json().hasNonNull("refresh_token")) cred.put("refreshToken", r.json().path("refresh_token").asText());
                }
                case "YOUCAN" -> {
                    HttpGateway.Res r = http.call(HttpGateway.Req.json("POST", "https://api.youcan.shop/oauth/token", Map.of("Accept", "application/json"),
                            Map.of("grant_type", "authorization_code", "client_id", app.clientId(), "client_secret", app.clientSecret(), "redirect_uri", apps.redirectUri(p), "code", code), "youcan-oauth"));
                    cred.put("accessToken", r.json().path("access_token").asText());
                    if (r.json().hasNonNull("refresh_token")) cred.put("refreshToken", r.json().path("refresh_token").asText());
                }
                case "AMAZON" -> {
                    cred.putAll(com.platform.integrations.adapters.AmazonAdapter.exchangeCode(http, app.clientId(), app.clientSecret(), code));
                    String seller = params.get("selling_partner_id");
                    if (seller == null || seller.isBlank()) return back + "?error=failed";
                    cred.put("sellerId", seller);
                }
                default -> { return back + "?error=failed"; }
            }
            if (cred.getOrDefault("accessToken", cred.get("refreshToken")) == null || cred.values().stream().anyMatch(String::isEmpty)) return back + "?error=failed";
            integrations.store(tenantId, userId, Providers.get(p), st[3], null, cred);
            return back + "?connected=" + p.toLowerCase();
        } catch (BusinessException | HttpGateway.ApiException e) {
            log.warn("{} login failed: {}", p, e.getMessage());
            return back + "?error=failed";
        } catch (RuntimeException e) {
            log.warn("{} login failed", p, e);
            return back + "?error=failed";
        }
    }

    private String sign(String payload) {
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + mac(p);
    }

    /** {tenant, user, provider, country} when the state is genuine and not expired. */
    private String[] verify(String state) {
        if (state == null) return null;
        int dot = state.lastIndexOf('.');
        if (dot < 1) return null;
        String p = state.substring(0, dot);
        if (!MessageDigest.isEqual(mac(p).getBytes(StandardCharsets.UTF_8), state.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8).split("\\.");
            if (parts.length != 5 || Long.parseLong(parts[4]) < Instant.now().getEpochSecond()) return null;
            UUID.fromString(parts[0]);
            UUID.fromString(parts[1]);
            return parts;
        } catch (Exception e) {
            return null;
        }
    }

    private String mac(String s) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}

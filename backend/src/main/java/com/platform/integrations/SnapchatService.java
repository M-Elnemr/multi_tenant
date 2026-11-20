package com.platform.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.TokenCrypto;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Snapchat, only what Snap's official documentation (notes in .mission/snapchat-official-notes.md) really offers a shop:
 * the owner logs in with Snap's OAuth (snapchat-marketing-api scope), and we make a Product Catalog in the chosen Organization with ONE scheduled Product Feed that Snap pulls from the shop's own feed address,
 * so prices and stock on Snapchat ads follow the shop. There is NO customer chat here: Snap's messaging endpoints are brand-to-creator collaboration messages behind a separate allowlist, with no incoming-message
 * webhook, so the assistant does not answer Snapchat customers and nothing in this class pretends it does.
 */
@Service
public class SnapchatService {
    private static final Logger log = LoggerFactory.getLogger(SnapchatService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final HttpGateway http;
    private final TokenCrypto crypto;
    private final OpenUrls urls;
    private final String clientId, clientSecret, rootDomain, accounts, ads;

    public SnapchatService(JdbcClient jdbc, HttpGateway http, TokenCrypto crypto, OpenUrls urls,
                           @Value("${app.snapchat.client-id:}") String clientId, @Value("${app.snapchat.client-secret:}") String clientSecret,
                           @Value("${app.platform.root-domain}") String rootDomain,
                           @Value("${app.snapchat.accounts-base:https://accounts.snapchat.com}") String accounts, @Value("${app.snapchat.ads-base:https://adsapi.snapchat.com}") String ads) {
        this.jdbc = jdbc;
        this.http = http;
        this.crypto = crypto;
        this.urls = urls;
        this.clientId = blank(clientId);
        this.clientSecret = blank(clientSecret);
        this.rootDomain = rootDomain;
        this.accounts = accounts;
        this.ads = ads;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public boolean available() { return clientId != null && clientSecret != null; }

    public String redirectUri() { return "https://" + rootDomain + "/api/v1/chat/snapchat/callback"; }

    // ---- login ------------------------------------------------------------------------------------------------

    private String sign(String payload) {
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("snap-state|" + p).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID verify(String state) {
        if (state == null || clientSecret == null || !state.contains(".")) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(state.substring(0, state.indexOf('.'))), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) return null;
            String[] p = payload.split("\\.");
            return p.length == 2 && Long.parseLong(p[1]) >= Instant.now().getEpochSecond() ? UUID.fromString(p[0]) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String startUrl(UUID tenantId) {
        if (!available()) throw BusinessException.badRequest("SNAPCHAT_NOT_CONFIGURED", "Connecting Snapchat is not set up on the server yet");
        String state = sign(tenantId + "." + (Instant.now().getEpochSecond() + 900));
        return accounts + "/login/oauth2/authorize?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri()) + "&scope=" + enc("snapchat-marketing-api") + "&state=" + enc(state);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private String tokens(JsonNode d, String oldRefresh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access", d.path("access_token").asText());
        m.put("refresh", d.path("refresh_token").asText(oldRefresh == null ? "" : oldRefresh));
        m.put("accessExp", Instant.now().getEpochSecond() + Math.max(60, d.path("expires_in").asLong(3600) - 120));
        try { return crypto.encrypt(JSON.writeValueAsString(m)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HttpGateway.Res token(Map<String, String> fields) {
        Map<String, String> f = new LinkedHashMap<>(fields);
        f.put("client_id", clientId);
        f.put("client_secret", clientSecret);
        return http.send(HttpGateway.Req.form(accounts + "/login/oauth2/access_token", Map.of(), f, "snapchat"));
    }

    /** Where the browser goes next: the shop's connections screen, with the result. */
    public String callback(String code, String state) {
        UUID tenantId = verify(state);
        if (tenantId == null) throw BusinessException.badRequest("INVALID_STATE", "This connection link has expired, please start again");
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Shop not found"));
        String back = "https://" + host + "/dashboard/channels/connect";
        if (code == null || code.isBlank()) return back + "?error=denied";
        try {
            HttpGateway.Res r = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri()));
            JsonNode d = r.json();
            if (!r.ok() || d.path("access_token").asText("").isEmpty()) { log.warn("Snapchat token exchange refused: HTTP {}", r.status()); return back + "?error=failed"; }
            List<Map<String, Object>> orgs = organizations(d.path("access_token").asText());
            String chosen = orgs.size() == 1 ? (String) orgs.get(0).get("id") : null;
            String state2 = orgs.isEmpty() ? "APPROVAL_REQUIRED" : chosen == null ? "CHOOSE_ORGANIZATION" : "CONNECTED";
            String note = orgs.isEmpty() ? "This Snapchat login has no Business Manager organization: create one at business.snapchat.com, then connect again." : null;
            jdbc.sql("""
                    INSERT INTO integrations.snapchat_links (tenant_id, token_enc, organizations, organization_id, organization_name, state, note)
                    VALUES (:t, :k, CAST(:o AS jsonb), :oid, :on, :s, :n)
                    ON CONFLICT (tenant_id) DO UPDATE SET token_enc = :k, organizations = CAST(:o AS jsonb), organization_id = :oid, organization_name = :on, state = :s, note = :n, updated_at = now()
                    """).param("t", tenantId).param("k", tokens(d, null)).param("o", JSON.writeValueAsString(orgs)).param("oid", chosen)
                    .param("on", chosen == null ? null : orgs.get(0).get("name")).param("s", state2).param("n", note).update();
            return back + "?connected=SNAPCHAT";
        } catch (Exception e) {
            log.warn("Snapchat login failed: {}", e.toString());
            return back + "?error=failed";
        }
    }

    private List<Map<String, Object>> organizations(String access) {
        HttpGateway.Res r = http.send(HttpGateway.Req.get(ads + "/v1/me/organizations", Map.of("Authorization", "Bearer " + access), "snapchat"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode o : r.json().path("organizations")) {
            JsonNode org = o.path("organization");
            if (org.path("id").asText("").isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", org.path("id").asText());
            m.put("name", org.path("name").asText(""));
            out.add(m);
        }
        return out;
    }

    // ---- token ------------------------------------------------------------------------------------------------

    private String access(UUID tenantId, String tokenEnc) {
        try {
            JsonNode t = JSON.readTree(crypto.decrypt(tokenEnc));
            if (t.path("accessExp").asLong(0) > Instant.now().getEpochSecond()) return t.path("access").asText();
            HttpGateway.Res r = token(Map.of("grant_type", "refresh_token", "refresh_token", t.path("refresh").asText()));
            JsonNode d = r.json();
            if (!r.ok() || d.path("access_token").asText("").isEmpty()) {
                jdbc.sql("UPDATE integrations.snapchat_links SET state = 'REAUTH_REQUIRED', note = 'Snapchat did not renew the login: connect Snapchat again.', updated_at = now() WHERE tenant_id = :t").param("t", tenantId).update();
                throw BusinessException.badRequest("SNAPCHAT_REAUTH", "The Snapchat login expired: connect Snapchat again");
            }
            jdbc.sql("UPDATE integrations.snapchat_links SET token_enc = :k, updated_at = now() WHERE tenant_id = :t").param("k", tokens(d, t.path("refresh").asText())).param("t", tenantId).update();
            return d.path("access_token").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BusinessException.badRequest("SNAPCHAT_REAUTH", "The Snapchat login is unreadable: connect Snapchat again");
        }
    }

    // ---- what the shop sees and does ---------------------------------------------------------------------------

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available());
        out.put("customerChat", false);   // Snap offers no customer messaging API: stated plainly so no screen implies otherwise
        var row = jdbc.sql("SELECT * FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) { out.put("state", "NOT_CONNECTED"); return out; }
        out.put("state", row.get("state"));
        out.put("note", row.get("note"));
        out.put("organizationId", row.get("organization_id"));
        out.put("organizationName", row.get("organization_name"));
        out.put("organizations", parse(String.valueOf(row.get("organizations"))));
        out.put("catalogId", row.get("catalog_id"));
        out.put("feedUrl", feedUrl(tenantId));
        if (row.get("feed_id") != null && "CONNECTED".equals(row.get("state"))) out.put("lastUpload", lastUpload(tenantId, (String) row.get("token_enc"), (String) row.get("feed_id")));
        return out;
    }

    private Object parse(String json) {
        try { return JSON.readValue(json, Object.class); } catch (Exception e) { return List.of(); }
    }

    private String feedUrl(UUID tenantId) { return urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/feeds/facebook.csv"; }

    private Map<String, Object> lastUpload(UUID tenantId, String tokenEnc, String feedId) {
        try {
            HttpGateway.Res r = http.send(HttpGateway.Req.get(ads + "/v1/product_feeds/" + enc(feedId) + "/feed_uploads", Map.of("Authorization", "Bearer " + access(tenantId, tokenEnc)), "snapchat"));
            JsonNode u = r.json().path("feed_uploads").path(0).path("feed_upload");
            if (u.isMissingNode()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", u.path("status").asText());
            m.put("totalItems", u.path("summary").path("total_items").asInt(0));
            m.put("itemsErrored", u.path("summary").path("items_errored").asInt(0));
            m.put("at", u.path("updated_at").asText(""));
            return m;
        } catch (RuntimeException e) {
            return null;   // the upload list is a courtesy; the connection itself is shown from our own record
        }
    }

    public Map<String, Object> chooseOrganization(UUID tenantId, String organizationId) {
        var row = jdbc.sql("SELECT organizations FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElseThrow(() -> BusinessException.badRequest("SNAPCHAT_NOT_CONNECTED", "Connect Snapchat first"));
        String name = null;
        try { for (JsonNode o : JSON.readTree(row)) if (o.path("id").asText().equals(organizationId)) name = o.path("name").asText(); } catch (Exception ignored) { }
        if (name == null) throw BusinessException.badRequest("SNAPCHAT_ORGANIZATION", "That organization is not one of this login's organizations");
        jdbc.sql("UPDATE integrations.snapchat_links SET organization_id = :o, organization_name = :n, catalog_id = NULL, feed_id = NULL, state = 'CONNECTED', note = NULL, updated_at = now() WHERE tenant_id = :t").param("o", organizationId).param("n", name).param("t", tenantId).update();
        return status(tenantId);
    }

    /** Makes the Catalog and its one scheduled Product Feed (once), pointing Snap at the shop's own feed address. Creating a catalog needs the Organization Admin role: if Snap refuses, the shop is told exactly that. */
    public Map<String, Object> publishCatalog(UUID tenantId) {
        var row = jdbc.sql("SELECT * FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.badRequest("SNAPCHAT_NOT_CONNECTED", "Connect Snapchat first"));
        if (row.get("organization_id") == null) throw BusinessException.badRequest("SNAPCHAT_ORGANIZATION", "Choose the Snapchat organization first");
        String access = access(tenantId, (String) row.get("token_enc"));
        Map<String, String> h = Map.of("Authorization", "Bearer " + access);
        String org = (String) row.get("organization_id"), catalog = (String) row.get("catalog_id");
        if (catalog == null) {
            String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
            HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", ads + "/v1/organizations/" + enc(org) + "/catalogs", h,
                    Map.of("catalogs", List.of(Map.of("organization_id", org, "name", "Elmanassa - " + shop, "vertical", "COMMERCE"))), "snapchat"));
            JsonNode c = r.json().path("catalogs").path(0);
            if (!r.ok() || !"SUCCESS".equalsIgnoreCase(c.path("sub_request_status").asText("SUCCESS")) || c.path("catalog").path("id").asText("").isEmpty()) throw refused(tenantId, r, "create the catalog");
            catalog = c.path("catalog").path("id").asText();
            jdbc.sql("UPDATE integrations.snapchat_links SET catalog_id = :c, updated_at = now() WHERE tenant_id = :t").param("c", catalog).param("t", tenantId).update();
        }
        if (row.get("feed_id") == null) {
            String currency = jdbc.sql("SELECT default_currency FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
            Map<String, Object> schedule = new LinkedHashMap<>();
            schedule.put("url", feedUrl(tenantId));
            schedule.put("interval_type", "HOURLY");
            schedule.put("interval_count", 1);
            schedule.put("minute", 15);
            schedule.put("timezone", "UTC");
            HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", ads + "/v1/catalogs/" + enc(catalog) + "/product_feeds", h,
                    Map.of("product_feeds", List.of(Map.of("catalog_id", catalog, "name", "Elmanassa products", "default_currency", currency, "status", "ACTIVE", "schedule", schedule))), "snapchat"));
            JsonNode f = r.json().path("product_feeds").path(0);
            if (!r.ok() || !"SUCCESS".equalsIgnoreCase(f.path("sub_request_status").asText("SUCCESS")) || f.path("product_feed").path("id").asText("").isEmpty()) throw refused(tenantId, r, "create the product feed");
            jdbc.sql("UPDATE integrations.snapchat_links SET feed_id = :f, state = 'CONNECTED', note = NULL, updated_at = now() WHERE tenant_id = :t").param("f", f.path("product_feed").path("id").asText()).param("t", tenantId).update();
        }
        return status(tenantId);
    }

    private BusinessException refused(UUID tenantId, HttpGateway.Res r, String what) {
        boolean forbidden = r.status() == 401 || r.status() == 403;
        String note = forbidden ? "Snapchat did not allow this login to " + what + ": the login must be an Organization Admin of the chosen organization."
                : "Snapchat could not " + what + " (HTTP " + r.status() + ").";
        jdbc.sql("UPDATE integrations.snapchat_links SET state = :s, note = :n, updated_at = now() WHERE tenant_id = :t").param("s", forbidden ? "APPROVAL_REQUIRED" : "ERROR").param("n", note).param("t", tenantId).update();
        return BusinessException.badRequest("SNAPCHAT_REFUSED", note);
    }

    public void disconnect(UUID tenantId) { jdbc.sql("DELETE FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tenantId).update(); }
}

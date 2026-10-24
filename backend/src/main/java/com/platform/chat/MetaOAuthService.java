package com.platform.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "Connect with Facebook": the owner logs in to Facebook once, picks their Page / Instagram account / WhatsApp number, and we store the tokens encrypted.
 * The login always returns to one fixed address on the platform host (Meta needs a registered redirect), so the shop and the person are carried in a
 * signed, short-lived {@code state}; the pages found are held encrypted for a few minutes until the owner chooses.
 */
@Service
public class MetaOAuthService {
    private static final Logger log = LoggerFactory.getLogger(MetaOAuthService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCOPES = "pages_show_list,pages_messaging,pages_manage_metadata,pages_read_engagement,instagram_basic,instagram_manage_messages,business_management,whatsapp_business_management,whatsapp_business_messaging,pages_manage_posts,instagram_content_publish,ads_management,ads_read";

    public record Item(String kind, String id, String name, String token, String linkedId, String detail) {}

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final TokenCrypto crypto;
    private final MetaChannelService channels;
    private final com.platform.marketing.AdsService ads;
    private final String appId;
    private final String appSecret;
    private final String rootDomain;
    private final String graphVersion;
    private final String configId;   // "Facebook Login for Business" apps choose permissions in a configuration instead of a scope list

    public MetaOAuthService(JdbcClient jdbc, MetaGraph graph, TokenCrypto crypto, MetaChannelService channels, com.platform.marketing.AdsService ads, @Value("${app.meta.app-id:}") String appId,
                            @Value("${app.meta.app-secret:}") String appSecret, @Value("${app.platform.root-domain}") String rootDomain, @Value("${app.meta.graph-version:v21.0}") String graphVersion,
                            @Value("${app.meta.login-config-id:}") String loginConfigId) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.crypto = crypto;
        this.channels = channels;
        this.ads = ads;
        this.appId = appId == null || appId.isBlank() ? null : appId.trim();
        this.appSecret = appSecret == null || appSecret.isBlank() ? null : appSecret.trim();
        this.rootDomain = rootDomain;
        this.graphVersion = graphVersion;
        this.configId = loginConfigId == null || loginConfigId.isBlank() ? null : loginConfigId.trim();
    }

    public boolean available() { return appId != null && appSecret != null; }

    public String redirectUri() { return "https://" + rootDomain + "/api/v1/chat/meta/callback"; }

    // ---- step 1: where to send the owner ---------------------------------------------------------------------

    public String startUrl(UUID tenantId, UUID userId) {
        if (!available()) throw BusinessException.badRequest("META_NOT_CONFIGURED", "Connecting with Facebook is not set up on the server yet");
        String state = sign(tenantId + "." + userId + "." + (Instant.now().getEpochSecond() + 900));
        return "https://www.facebook.com/" + graphVersion + "/dialog/oauth?client_id=" + enc(appId) + "&redirect_uri=" + enc(redirectUri()) + "&state=" + enc(state)
                + (configId != null ? "&config_id=" + enc(configId) + "&override_default_response_type=true" : "&scope=" + enc(SCOPES)) + "&response_type=code";
    }

    // ---- step 2: Facebook sent the owner back with a code ----------------------------------------------------

    /** Returns where to send the browser next: the shop's dashboard, with the id of the list to choose from (or an error code). */
    public String callback(String code, String state) {
        String[] st = verify(state);
        if (st == null) throw BusinessException.badRequest("INVALID_STATE", "This connection link has expired, please start again");
        UUID tenantId = UUID.fromString(st[0]), userId = UUID.fromString(st[1]);
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Shop not found"));
        String back = "https://" + host + "/dashboard/ai";
        if (code == null || code.isBlank()) return back + "?metaError=denied";
        try {
            String shortToken = String.valueOf(graph.get("oauth/access_token?client_id=" + enc(appId) + "&client_secret=" + enc(appSecret) + "&redirect_uri=" + enc(redirectUri()) + "&code=" + enc(code), null).get("access_token"));
            String userToken = String.valueOf(graph.get("oauth/access_token?grant_type=fb_exchange_token&client_id=" + enc(appId) + "&client_secret=" + enc(appSecret) + "&fb_exchange_token=" + enc(shortToken), null).get("access_token"));
            List<Item> items = discover(userToken);
            if (items.isEmpty()) return back + "?metaError=nothing";
            UUID id = jdbc.sql("INSERT INTO chat.oauth_pending (tenant_id, user_id, payload_enc) VALUES (:t, :u, :p) RETURNING id").param("t", tenantId).param("u", userId).param("p", crypto.encrypt(JSON.writeValueAsString(items))).query(UUID.class).single();
            return back + "?connect=" + id;
        } catch (MetaGraph.MetaException e) {
            log.warn("Facebook login exchange failed: {}", e.getMessage());
            return back + "?metaError=failed";
        } catch (Exception e) {
            log.warn("Facebook login failed", e);
            return back + "?metaError=failed";
        }
    }

    private static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }

    @SuppressWarnings("unchecked")
    private List<Item> discover(String userToken) {
        List<Item> out = new ArrayList<>();
        Map<String, Object> accounts;
        try {
            accounts = graph.get("me/accounts?fields=id,name,access_token,instagram_business_account{id,username}&limit=50", userToken);
        } catch (MetaGraph.MetaException e) {
            if (e.code() != 100 && e.code() != 10 && e.code() != 200) throw e;
            accounts = graph.get("me/accounts?fields=id,name,access_token&limit=50", userToken);   // without the Instagram link when the app lacks that access
        }
        for (Object o : (List<Object>) accounts.getOrDefault("data", List.of())) {
            Map<String, Object> pg = (Map<String, Object>) o;
            String pageToken = String.valueOf(pg.get("access_token"));
            out.add(new Item("FACEBOOK", String.valueOf(pg.get("id")), String.valueOf(pg.get("name")), pageToken, null, null));
            if (pg.get("instagram_business_account") instanceof Map<?, ?> ig)
                out.add(new Item("INSTAGRAM", String.valueOf(ig.get("id")), "@" + ig.get("username"), pageToken, String.valueOf(pg.get("id")), String.valueOf(pg.get("name"))));
        }
        try {
            Map<String, Object> biz = graph.get("me/businesses?fields=id,name,owned_whatsapp_business_accounts{id,name,phone_numbers{id,display_phone_number,verified_name}}&limit=25", userToken);
            for (Object b : (List<Object>) biz.getOrDefault("data", List.of())) {
                Object wabas = ((Map<String, Object>) b).get("owned_whatsapp_business_accounts");
                if (!(wabas instanceof Map<?, ?> wm)) continue;
                for (Object w : list(wm.get("data"))) {
                    Map<String, Object> waba = (Map<String, Object>) w;
                    Object phones = waba.get("phone_numbers");
                    if (!(phones instanceof Map<?, ?> pm)) continue;
                    for (Object ph : list(pm.get("data"))) {
                        Map<String, Object> phone = (Map<String, Object>) ph;
                        out.add(new Item("WHATSAPP", String.valueOf(phone.get("id")), phone.get("display_phone_number") + " · " + phone.getOrDefault("verified_name", ""), userToken, String.valueOf(waba.get("id")), String.valueOf(waba.get("name"))));
                    }
                }
            }
        } catch (MetaGraph.MetaException e) {
            log.debug("No WhatsApp accounts found: {}", e.getMessage());   // the login worked; this account just has none we can see
        }
        try {   // ad accounts the person can run ads on (needs the ads permissions; a login without them simply has none to offer)
            Map<String, Object> accts = graph.get("me/adaccounts?fields=id,name,currency,account_status&limit=50", userToken);
            for (Object a : (List<Object>) accts.getOrDefault("data", List.of())) {
                Map<String, Object> ac = (Map<String, Object>) a;
                if (!"1".equals(String.valueOf(ac.get("account_status")))) continue;   // only active accounts can run ads
                out.add(new Item("ADACCOUNT", String.valueOf(ac.get("id")), String.valueOf(ac.getOrDefault("name", ac.get("id"))), userToken, String.valueOf(ac.getOrDefault("currency", "EGP")), String.valueOf(ac.getOrDefault("currency", ""))));
            }
        } catch (MetaGraph.MetaException e) {
            log.debug("No ad accounts found: {}", e.getMessage());
        }
        return out;
    }

    // ---- step 3: the owner chooses ---------------------------------------------------------------------------

    /** What the owner can pick (never the tokens). */
    public List<Map<String, Object>> pending(UUID tenantId, UUID userId, UUID id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : load(tenantId, userId, id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", i.kind());
            m.put("id", i.id());
            m.put("name", i.name());
            m.put("detail", i.detail());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> connectChosen(UUID tenantId, UUID userId, UUID id, List<String> keys) {
        List<Item> items = load(tenantId, userId, id);
        List<Map<String, Object>> done = new ArrayList<>();
        for (Item i : items) {
            if (keys == null || !keys.contains(i.kind() + ":" + i.id())) continue;
            done.add("ADACCOUNT".equals(i.kind()) ? ads.connectAccount(tenantId, userId, i.id(), i.name(), i.token(), i.linkedId()) : channels.connect(tenantId, userId, i.kind(), i.id(), i.token(), i.linkedId()));
        }
        if (done.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose at least one");
        jdbc.sql("DELETE FROM chat.oauth_pending WHERE id = :i").param("i", id).update();
        return done;
    }

    private List<Item> load(UUID tenantId, UUID userId, UUID id) {
        var row = jdbc.sql("SELECT payload_enc FROM chat.oauth_pending WHERE id = :i AND tenant_id = :t AND user_id = :u AND created_at > now() - interval '30 minutes'")
                .param("i", id).param("t", tenantId).param("u", userId).query(String.class).optional().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "This list has expired, connect again"));
        try { return JSON.readValue(crypto.decrypt(row), new TypeReference<List<Item>>() {}); } catch (Exception e) { throw BusinessException.notFound("RESOURCE_NOT_FOUND", "This list has expired, connect again"); }
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void purge() { jdbc.sql("DELETE FROM chat.oauth_pending WHERE created_at < now() - interval '30 minutes'").update(); }

    // ---- signed state ----------------------------------------------------------------------------------------

    private String sign(String payload) {
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + mac(p);
    }

    /** {tenantId, userId} when the state is genuine and not expired. */
    private String[] verify(String state) {
        if (state == null || appSecret == null) return null;
        int dot = state.lastIndexOf('.');
        if (dot < 1) return null;
        String p = state.substring(0, dot);
        if (!MessageDigest.isEqual(mac(p).getBytes(StandardCharsets.UTF_8), state.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8).split("\\.");
            if (parts.length != 3 || Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) return null;
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
            m.init(new SecretKeySpec(("oauth-state|" + appSecret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}

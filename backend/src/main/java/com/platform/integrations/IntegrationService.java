package com.platform.integrations;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connected accounts: marketplace sellers, stores on other platforms, carriers. Credentials are validated against the platform first and stored encrypted. */
@Service
public class IntegrationService {
    private static final int MAX_ACCOUNTS = 40;

    private final JdbcClient jdbc;
    private final IntegrationCrypto crypto;
    private final HttpGateway http;
    private final AuditService audit;
    private final OAuthApps oauth;
    private final Map<String, ChannelAdapter> adapters;
    private final Map<String, com.platform.integrations.carriers.CarrierAdapter> carriers;

    public IntegrationService(JdbcClient jdbc, IntegrationCrypto crypto, HttpGateway http, AuditService audit, OAuthApps oauth, List<ChannelAdapter> adapters, List<com.platform.integrations.carriers.CarrierAdapter> carrierAdapters) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.http = http;
        this.audit = audit;
        this.oauth = oauth;
        this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::provider, Function.identity()));
        this.carriers = carrierAdapters.stream().collect(Collectors.toMap(com.platform.integrations.carriers.CarrierAdapter::provider, Function.identity()));
    }

    public com.platform.integrations.carriers.CarrierAdapter carrier(String provider) {
        var a = carriers.get(provider);
        if (a == null) throw BusinessException.badRequest("PROVIDER_UNAVAILABLE", "This carrier is not available yet");
        return a;
    }

    public ChannelAdapter adapter(String provider) {
        ChannelAdapter a = adapters.get(provider);
        if (a == null) throw BusinessException.badRequest("PROVIDER_UNAVAILABLE", "This platform is not available yet");
        return a;
    }

    /** What the connect screen shows: every platform with the countries it serves and what the owner has to provide. Optionally narrowed to one country. */
    public List<Map<String, Object>> catalog(String country, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Providers.Def d : Providers.all()) {
            if (country != null && !country.isBlank() && !d.countries().contains(country.toUpperCase())) continue;
            if (kind != null && !kind.isBlank() && !d.kind().equals(kind)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name());
            m.put("kind", d.kind());
            m.put("countries", d.countries());
            m.put("status", d.status());
            m.put("can", d.can());
            m.put("fields", d.fields().stream().map((f) -> Map.of("key", f.key(), "labelEn", f.labelEn(), "labelAr", f.labelAr(), "secret", f.secret(), "placeholder", f.placeholder(), "multiline", f.multiline(), "optional", isOptional(f))).toList());
            m.put("oauth", d.oauth() && oauthReady(d.id()));
            m.put("helpEn", d.helpEn());
            m.put("helpAr", d.helpAr());
            m.put("docsUrl", d.docsUrl());
            out.add(m);
        }
        return out;
    }

    private boolean oauthReady(String provider) { return "META_ADS".equals(provider) || oauth.of(provider).ok(); }

    private static boolean isOptional(Providers.Field f) { return f.labelEn().toLowerCase().contains("optional"); }

    // ---- reading -----------------------------------------------------------------------------------------------

    private static final String VIEW = """
            SELECT a.id, a.provider, a.kind, a.country, a.label, a.external_id, a.status, a.last_sync_at, a.last_error, a.created_at, a.settings::text AS settings,
              (SELECT count(*) FROM integrations.listings l WHERE l.account_id = a.id) AS listings,
              (SELECT count(*) FROM integrations.orders o WHERE o.account_id = a.id) AS orders,
              (SELECT count(*) FROM integrations.orders o WHERE o.account_id = a.id AND o.stage = 'NEW') AS new_orders
            FROM integrations.accounts a
            """;

    public List<Map<String, Object>> accounts(UUID tenantId, String kind) {
        return Rows.camel(jdbc.sql(VIEW + " WHERE a.tenant_id = :t AND (CAST(:k AS varchar) IS NULL OR a.kind = CAST(:k AS varchar)) ORDER BY a.created_at").param("t", tenantId).param("k", blank(kind)).query().listOfRows())
                .stream().map(this::decorate).toList();
    }

    public Map<String, Object> account(UUID tenantId, UUID id) {
        return decorate(Rows.camel(jdbc.sql(VIEW + " WHERE a.tenant_id = :t AND a.id = :i").param("t", tenantId).param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found"))));
    }

    private Map<String, Object> decorate(Map<String, Object> m) {
        Providers.Def d = Providers.get((String) m.get("provider"));
        m.put("name", d == null ? m.get("provider") : d.name());
        m.put("can", d == null ? List.of() : d.can());
        m.put("providerStatus", d == null ? "SOON" : d.status());
        Object stored = m.get("settings") instanceof String js ? Rows.json(js).get("adminUrl") : null;
        m.put("adminUrl", stored != null ? stored : adminUrl((String) m.get("provider"), (String) m.get("country"), (String) m.get("externalId"), (String) m.get("label")));
        m.remove("settings");
        m.put("currency", Providers.currencyOf((String) m.get("country")));
        return m;
    }

    /** Where the owner manages the shop on the platform itself ("full access"): opens in a new tab. */
    static String adminUrl(String provider, String country, String externalId, String label) {
        return switch (provider) {
            case "AMAZON" -> "https://sellercentral.amazon." + country.toLowerCase();
            case "NOON" -> "https://partners.noon.com";
            case "SALLA" -> "https://s.salla.sa";
            case "YOUCAN" -> "https://seller-area.youcan.shop";
            case "ARAMEX" -> "https://www.aramex.com";
            case "BOSTA" -> "https://business.bosta.co";
            case "MYLERZ" -> "https://mylerz.net";
            default -> null;
        };
    }

    // ---- connecting --------------------------------------------------------------------------------------------

    public record ConnectReq(String provider, String country, String label, Map<String, String> credentials) {}

    /** Validates the credentials with the platform, stores the account and queues the first import. */
    @Transactional
    public Map<String, Object> connect(UUID tenantId, UUID actor, ConnectReq r) {
        Providers.Def d = Providers.get(r.provider());
        if (d == null || "SOON".equals(d.status())) throw BusinessException.badRequest("PROVIDER_UNAVAILABLE", "This platform is not available yet");
        String country = r.country() == null ? "EG" : r.country().toUpperCase();
        if (!d.countries().contains(country)) throw BusinessException.badRequest("COUNTRY_NOT_SUPPORTED", d.name() + " is not available for this country");
        Map<String, String> cred = new LinkedHashMap<>();
        for (Providers.Field f : d.fields()) {
            String v = r.credentials() == null ? null : r.credentials().get(f.key());
            v = v == null ? "" : v.trim();
            if (v.isEmpty() && !isOptional(f) && !"AMAZON".equals(d.id())) throw BusinessException.badRequest("VALIDATION_ERROR", f.labelEn() + " is required");
            if (v.length() > 6000) throw BusinessException.badRequest("VALIDATION_ERROR", "A value is too long");
            if (!v.isEmpty()) cred.put(f.key(), v);
        }
        if ("AMAZON".equals(d.id())) {   // sellerId + refresh token are always needed; the app keys may be elmanassa's own
            if (!cred.containsKey("sellerId") || !cred.containsKey("refreshToken")) throw BusinessException.badRequest("VALIDATION_ERROR", "Seller ID and refresh token are required");
        }
        return store(tenantId, actor, d, country, r.label(), cred);
    }

    /** Shared by the typed-credentials path and the OAuth callback. */
    @Transactional
    public Map<String, Object> store(UUID tenantId, UUID actor, Providers.Def d, String country, String label, Map<String, String> cred) {
        long existing = jdbc.sql("SELECT count(*) FROM integrations.accounts WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single();
        if (existing >= MAX_ACCOUNTS) throw BusinessException.badRequest("PLAN_LIMIT_REACHED", "Too many connected accounts");
        Ext.AccountInfo info;
        if (d.kind().equals(Providers.CARRIER)) {   // a carrier is checked with its own credentials test; its account has no product or order feed
            try { carrier(d.id()).validate(ctx(tenantId, null, cred, country)); } catch (HttpGateway.ApiException e) { throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_FAILED", e.getMessage()); }
        }
        if (d.kind().equals(Providers.CARRIER) || d.kind().equals(Providers.ADS)) info = new Ext.AccountInfo(cred.getOrDefault("adAccountId", cred.getOrDefault("accountNumber", cred.getOrDefault("username", cred.getOrDefault("userName", d.id().toLowerCase())))), d.name(), Providers.currencyOf(country), null);
        else {
            try {
                info = adapter(d.id()).connect(new ChannelAdapter.Ctx(cred, country, http, d.id() + ":" + tenantId, (x) -> {}));
            } catch (HttpGateway.ApiException e) {
                throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_FAILED", e.getMessage());
            }
            if (info.updatedCredentials() != null) cred.putAll(info.updatedCredentials());
        }
        String name = label != null && !label.isBlank() ? label.trim() : info.label();
        String admin = "SHOPIFY".equals(d.id()) && cred.get("shopDomain") != null ? "https://" + cred.get("shopDomain").toLowerCase().replaceFirst("^https?://", "").replaceAll("[/?#].*$", "") + "/admin"
                : "WOOCOMMERCE".equals(d.id()) && cred.get("siteUrl") != null ? UrlGuard.requirePublicHttps(cred.get("siteUrl")) + "/wp-admin" : null;
        UUID id = jdbc.sql("""
                INSERT INTO integrations.accounts (tenant_id, provider, kind, country, label, external_id, credentials_enc, status, created_by, settings)
                VALUES (:t, :p, :k, :c, :l, :e, :cr, 'ACTIVE', :u, CAST(:s AS jsonb))
                ON CONFLICT (tenant_id, provider, country, external_id) DO UPDATE SET credentials_enc = :cr, label = :l, status = 'ACTIVE', last_error = NULL, settings = CAST(:s AS jsonb), updated_at = now()
                RETURNING id
                """).param("s", admin == null ? "{}" : "{\"adminUrl\":\"" + admin + "\"}").param("t", tenantId).param("p", d.id()).param("k", d.kind()).param("c", country).param("l", name).param("e", info.externalId() == null ? "" : info.externalId())
                .param("cr", crypto.encrypt(cred)).param("u", actor).query(UUID.class).single();
        audit.record(actor, tenantId, "INTEGRATION_CONNECTED", "integration_account", id, "{\"provider\":\"" + d.id() + "\",\"country\":\"" + country + "\"}");
        if (!d.kind().equals(Providers.CARRIER) && !d.kind().equals(Providers.ADS)) {
            if (Providers.supports(d, "PRODUCTS")) enqueue(tenantId, id, "IMPORT_PRODUCTS", Map.of());
            if (Providers.supports(d, "ORDERS")) enqueue(tenantId, id, "PULL_ORDERS", Map.of("full", true));
        }
        return account(tenantId, id);
    }

    /** Adds or changes credential fields of a connected account (for example a noon warehouse code) and checks them again. */
    @Transactional
    public Map<String, Object> updateCredentials(UUID tenantId, UUID actor, UUID id, String label, Map<String, String> changes) {
        var row = row(tenantId, id);
        Providers.Def d = Providers.get((String) row.get("provider"));
        Map<String, String> cred = crypto.decrypt((String) row.get("credentials_enc"));
        if (changes != null) for (Providers.Field f : d.fields()) { String v = changes.get(f.key()); if (v != null && !v.isBlank()) cred.put(f.key(), v.trim()); }
        if (!d.kind().equals(Providers.CARRIER) && !d.kind().equals(Providers.ADS)) {
            try { adapter(d.id()).connect(ctx(tenantId, id, cred, (String) row.get("country"))); } catch (HttpGateway.ApiException e) { throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_FAILED", e.getMessage()); }
        }
        jdbc.sql("UPDATE integrations.accounts SET credentials_enc = :c, label = coalesce(nullif(btrim(:l), ''), label), status = 'ACTIVE', last_error = NULL, updated_at = now() WHERE id = :i AND tenant_id = :t")
                .param("c", crypto.encrypt(cred)).param("l", label).param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "INTEGRATION_UPDATED", "integration_account", id, null);
        return account(tenantId, id);
    }

    @Transactional
    public void disconnect(UUID tenantId, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM integrations.accounts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found");
        audit.record(actor, tenantId, "INTEGRATION_DISCONNECTED", "integration_account", id, null);
    }

    /** Checks the saved credentials against the platform right now. */
    public Map<String, Object> test(UUID tenantId, UUID id) {
        var row = row(tenantId, id);
        Providers.Def d = Providers.get((String) row.get("provider"));
        if (d.kind().equals(Providers.CARRIER) || d.kind().equals(Providers.ADS)) return account(tenantId, id);
        try {
            adapter(d.id()).connect(ctx(tenantId, id, crypto.decrypt((String) row.get("credentials_enc")), (String) row.get("country")));
            jdbc.sql("UPDATE integrations.accounts SET status = 'ACTIVE', last_error = NULL WHERE id = :i").param("i", id).update();
        } catch (HttpGateway.ApiException e) {
            markError(id, e.getMessage());
        }
        return account(tenantId, id);
    }

    // ---- internals shared with the other services ---------------------------------------------------------------

    public Map<String, Object> row(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT id, tenant_id, provider, kind, country, label, external_id, credentials_enc, settings, status FROM integrations.accounts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found"));
    }

    public Map<String, Object> rowAny(UUID id) {
        return jdbc.sql("SELECT id, tenant_id, provider, kind, country, label, external_id, credentials_enc, settings, status FROM integrations.accounts WHERE id = :i").param("i", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found"));
    }

    public ChannelAdapter.Ctx ctx(UUID tenantId, UUID accountId, Map<String, String> cred, String country) {
        return new ChannelAdapter.Ctx(cred, country, http, "acct:" + (accountId == null ? tenantId : accountId), (updated) -> {
            if (accountId == null) return;
            Map<String, String> merged = new LinkedHashMap<>(cred);
            merged.putAll(updated);
            jdbc.sql("UPDATE integrations.accounts SET credentials_enc = :c, updated_at = now() WHERE id = :i").param("c", crypto.encrypt(merged)).param("i", accountId).update();
        });
    }

    public ChannelAdapter.Ctx ctxOf(Map<String, Object> row) {
        Map<String, String> cred = crypto.decrypt((String) row.get("credentials_enc"));
        return ctx((UUID) row.get("tenant_id"), (UUID) row.get("id"), cred, (String) row.get("country"));
    }

    public Map<String, String> credentials(Map<String, Object> row) { return crypto.decrypt((String) row.get("credentials_enc")); }

    public void markError(UUID accountId, String message) {
        jdbc.sql("UPDATE integrations.accounts SET status = 'ERROR', last_error = :m, updated_at = now() WHERE id = :i").param("i", accountId).param("m", clip(message)).update();
    }

    public void markOk(UUID accountId) {
        jdbc.sql("UPDATE integrations.accounts SET status = 'ACTIVE', last_error = NULL, last_sync_at = now(), updated_at = now() WHERE id = :i").param("i", accountId).update();
    }

    public void enqueue(UUID tenantId, UUID accountId, String type, Map<String, Object> payload) {
        try {
            jdbc.sql("INSERT INTO integrations.jobs (tenant_id, account_id, type, payload) SELECT :t, :a, :ty, CAST(:p AS jsonb) WHERE NOT EXISTS (SELECT 1 FROM integrations.jobs WHERE account_id = :a AND type = :ty AND status = 'PENDING')")
                    .param("t", tenantId).param("a", accountId).param("ty", type).param("p", HttpGateway.JSON.writeValueAsString(payload)).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String clip(String s) { return s == null ? null : (s.length() > 480 ? s.substring(0, 480) : s); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}

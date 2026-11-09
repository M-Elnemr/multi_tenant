package com.platform.commerce.domains;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.integrations.HttpGateway;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ResellerClub reseller HTTP API ({@code httpapi.com} live, a sandbox host for testing), authenticated with
 * {@code auth-userid} + {@code api-key} as plain query/form parameters (the account is IP-whitelisted, so no
 * client certificate is involved). This is the ONLY class in the codebase that knows ResellerClub's request/response
 * shapes - every one of its HTTP calls goes through {@link #call}, so correcting a wrong endpoint or param name
 * later never touches {@code QuoteService}, {@code DomainOrderService} or {@code DomainOrderRunner}.
 *
 * <p><b>Confidence notes (read before ever flipping {@code app.domains.registrar.enabled=true} in production):</b>
 * <ul>
 *   <li><b>Confirmed / high confidence</b> (long-standing, stable parts of ResellerClub's public reseller API docs):
 *       {@link #checkAvailability} ({@code domains/available.json}), {@link #getDomainDetails} ({@code domains/orders/id.json}),
 *       the {@code domains/orderid/new.json} order-id lookup used before registering, and the auth param style itself.</li>
 *   <li><b>Inferred / best-guess, NOT verified against a live sandbox</b>: {@link #getPricing} (the reseller price-list
 *       endpoint's exact JSON nesting and slab keys vary by account tier and were reconstructed from general docs,
 *       not confirmed), the customer/contact creation calls inside {@link #registerDomain} (a real registration needs a
 *       pre-created customer-id and contact-id; the exact signup field names here are a reasonable reconstruction, not
 *       a verified one), {@link #updateNameservers} and every DNS method ({@link #getDnsRecords}, {@link #createDnsRecord},
 *       {@link #updateDnsRecord}, {@link #deleteDnsRecord}) - ResellerClub's DNS-zone management endpoints are the part
 *       of their API with the least stable public documentation, so these paths are the most likely to need correction
 *       once real sandbox credentials exist.</li>
 * </ul>
 * None of this runs until {@code app.domains.registrar.enabled=true} AND real credentials are set - the services that
 * call this class check the flag first and never construct a request otherwise.
 */
@Component
public class ResellerClubProvider implements DomainProvider {
    private final HttpGateway http;
    private final String userId;
    private final String apiKey;
    private final String baseUrl;
    private final List<String> defaultNameservers;
    /** domain -> (customerId, contactId) resolved once per JVM so a retry does not re-create a customer/contact every time. */
    private final Map<String, String[]> resolvedParty = new ConcurrentHashMap<>();

    public ResellerClubProvider(HttpGateway http,
                                @Value("${app.domains.resellerclub.user-id:}") String userId,
                                @Value("${app.domains.resellerclub.api-key:}") String apiKey,
                                @Value("${app.domains.resellerclub.sandbox-url:https://test.httpapi.com/api}") String sandboxUrl,
                                @Value("${app.domains.resellerclub.live-url:https://httpapi.com/api}") String liveUrl,
                                @Value("${app.domains.resellerclub.use-sandbox:true}") boolean useSandbox,
                                @Value("${app.domains.resellerclub.default-nameservers:ns1.resellerclub.test,ns2.resellerclub.test}") String defaultNameservers) {
        this.http = http;
        this.userId = userId;
        this.apiKey = apiKey;
        this.baseUrl = useSandbox ? sandboxUrl : liveUrl;
        this.defaultNameservers = java.util.Arrays.stream(defaultNameservers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override public String code() { return "resellerclub"; }

    // ---- the one place that builds a ResellerClub HTTP call -------------------------------------------------

    private HttpGateway.Res call(String method, String path, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("auth-userid", userId);
        all.put("api-key", apiKey);
        try {
            if ("GET".equals(method)) {
                StringBuilder qs = new StringBuilder(baseUrl).append(path).append('?');
                all.forEach((k, v) -> { if (v != null) qs.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'); });
                return http.call(HttpGateway.Req.get(qs.toString(), Map.of(), "resellerclub"));
            }
            Map<String, String> clean = new LinkedHashMap<>();
            all.forEach((k, v) -> { if (v != null) clean.put(k, v); });
            return http.call(HttpGateway.Req.form(baseUrl + path, Map.of(), clean, "resellerclub"));
        } catch (HttpGateway.ApiException e) {
            throw translate(e);
        }
    }

    /** Maps a transport failure to a stable {@link ProviderException} code; the registrar's own error text never reaches the caller as-is. */
    private static ProviderException translate(HttpGateway.ApiException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (e.status == 429 || msg.contains("rate") || msg.contains("throttl")) return ProviderException.rateLimited(e.getMessage());
        if (e.status >= 500 || msg.contains("timeout") || msg.contains("timed out")) return ProviderException.providerDown(e.getMessage());
        if (msg.contains("already") && msg.contains("regist")) return ProviderException.alreadyRegistered(e.getMessage());
        if (msg.contains("contact") || msg.contains("invalid") && msg.contains("address")) return ProviderException.invalidContact(e.getMessage());
        if (e.status == 404) return ProviderException.notFound(e.getMessage());
        return ProviderException.unknown(e.getMessage());
    }

    private static String[] splitDomain(String domain) {
        int dot = domain.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("Not a domain: " + domain);
        return new String[] {domain.substring(0, dot), domain.substring(dot + 1)};
    }

    // ---- availability / pricing (confirmed shape) ------------------------------------------------------------

    @Override
    public List<AvailabilityResult> checkAvailability(List<String> domains) {
        List<AvailabilityResult> out = new ArrayList<>();
        for (String domain : domains) {
            String[] parts = splitDomain(domain);
            var res = call("GET", "/domains/available.json", Map.of("domain-name", parts[0], "tlds", parts[1]));
            JsonNode node = res.json().path(domain);
            if (node.isMissingNode()) node = res.json().path(parts[0] + "." + parts[1]);
            String status = node.path("status").asText("unknown");
            boolean available = "available".equalsIgnoreCase(status);
            boolean premium = node.path("classkey").asText("").toLowerCase().contains("premium");
            if ("unknown".equalsIgnoreCase(status) && node.isMissingNode()) throw ProviderException.providerDown("No availability answer for " + domain);
            out.add(new AvailabilityResult(domain, available, premium, 0, "USD"));
        }
        return out;
    }

    /** INFERRED, not verified live: see the class Javadoc. */
    @Override
    public List<TldPrice> getPricing(List<String> tlds) {
        List<TldPrice> out = new ArrayList<>();
        for (String tld : tlds) {
            String productKey = "dot" + tld;   // ResellerClub's product-key convention for the plain gTLD slabs (dotcom, dotnet, dotorg, ...) - not confirmed for every TLD
            var res = call("GET", "/products/reseller-price.json", Map.of("product-key", productKey));
            JsonNode slab = res.json().path(productKey);
            double registerUsd = slab.path("addnewdomain").path("1").asDouble(10.0);   // sane non-zero fallback so a broken lookup never prices a domain at 0
            double renewUsd = slab.path("renewdomain").path("1").asDouble(registerUsd);
            out.add(new TldPrice(tld, Math.round(registerUsd * 100), Math.round(renewUsd * 100), "USD"));
        }
        return out;
    }

    // ---- registration (orderid/new.json + register.json are the confirmed parts; customer/contact creation is inferred) ----

    @Override
    public RegisterResult registerDomain(RegisterRequest req) {
        String[] parts = splitDomain(req.domain());
        String[] party = resolvedParty.computeIfAbsent(req.domain(), d -> ensureParty(req.registrant()));
        String customerId = party[0], contactId = party[1];

        // domains/orderid/new.json: reserves a numeric order id for a not-yet-registered domain (confirmed shape).
        var idRes = call("GET", "/domains/orderid/new.json", Map.of("domain-name", req.domain()));
        String orderId = idRes.json().has("orderid") ? idRes.json().get("orderid").asText() : idRes.body();

        List<String> ns = req.nameservers() == null || req.nameservers().isEmpty() ? defaultNameservers : req.nameservers();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("domain-name", req.domain());
        params.put("years", String.valueOf(req.years()));
        params.put("customer-id", customerId);
        params.put("reg-contact-id", contactId);
        params.put("admin-contact-id", contactId);
        params.put("tech-contact-id", contactId);
        params.put("billing-contact-id", contactId);
        params.put("invoice-option", "NoInvoice");
        for (int i = 0; i < ns.size(); i++) params.put("ns" + (i + 1), ns.get(i));
        var res = call("POST", "/domains/register.json", params);
        JsonNode j = res.json();
        if (j.path("status").asText("").equalsIgnoreCase("error")) throw translate(new HttpGateway.ApiException(409, HttpGateway.describe(res)));
        String providerOrderId = j.has("entityid") ? j.get("entityid").asText() : orderId;
        return new RegisterResult(providerOrderId, Instant.now().plus(365L * Math.max(1, req.years()), java.time.temporal.ChronoUnit.DAYS));
    }

    /** INFERRED: customers/v2/signup.json + contacts/add.json, reconstructed from general docs, not confirmed live. */
    private String[] ensureParty(RegistrantInfo r) {
        if (r == null || r.email() == null || r.email().isBlank()) throw ProviderException.invalidContact("Registrant contact is required");
        Map<String, String> customerParams = new LinkedHashMap<>();
        customerParams.put("username", r.email());
        customerParams.put("passwd", java.util.UUID.randomUUID().toString());
        customerParams.put("lang", "en");
        customerParams.put("company", r.org() == null ? r.name() : r.org());
        customerParams.put("address-line-1", r.address());
        customerParams.put("city", r.city() == null ? "" : r.city());
        customerParams.put("state", r.state() == null ? "" : r.state());
        customerParams.put("country", r.country());
        customerParams.put("zipcode", r.postalCode() == null ? "" : r.postalCode());
        customerParams.put("phone-cc", "");
        customerParams.put("phone", r.phone());
        var custRes = call("GET", "/customers/v2/signup.json", customerParams);
        String customerId = custRes.json().has("customerid") ? custRes.json().get("customerid").asText() : custRes.body();

        Map<String, String> contactParams = new LinkedHashMap<>(customerParams);
        contactParams.remove("username");
        contactParams.remove("passwd");
        contactParams.remove("lang");
        contactParams.put("name", r.name());
        contactParams.put("email", r.email());
        contactParams.put("customer-id", customerId);
        contactParams.put("type", "Contact");
        var contactRes = call("GET", "/contacts/add.json", contactParams);
        String contactId = contactRes.json().has("contactid") ? contactRes.json().get("contactid").asText() : contactRes.body();
        return new String[] {customerId, contactId};
    }

    @Override
    public RenewResult renewDomain(String domain, String providerOrderId, int years) {
        var res = call("POST", "/domains/renew.json", Map.of("order-id", providerOrderId, "years", String.valueOf(years), "exp-date", ""));
        JsonNode j = res.json();
        if (j.path("status").asText("").equalsIgnoreCase("error")) throw translate(new HttpGateway.ApiException(409, HttpGateway.describe(res)));
        return new RenewResult(providerOrderId, Instant.now().plus(365L * Math.max(1, years), java.time.temporal.ChronoUnit.DAYS));
    }

    @Override
    public TransferResult transferDomain(TransferRequest req) {
        String[] party = ensureParty(req.registrant()) == null ? new String[] {null, null} : resolvedParty.computeIfAbsent(req.domain(), d -> ensureParty(req.registrant()));
        var res = call("POST", "/domains/transfer.json", Map.of("domain-name", req.domain(), "auth-code", req.authCode(),
                "customer-id", String.valueOf(party[0]), "reg-contact-id", String.valueOf(party[1]), "admin-contact-id", String.valueOf(party[1]),
                "tech-contact-id", String.valueOf(party[1]), "billing-contact-id", String.valueOf(party[1]), "invoice-option", "NoInvoice"));
        JsonNode j = res.json();
        String orderId = j.has("entityid") ? j.get("entityid").asText() : res.body();
        return new TransferResult(orderId, "PENDING");
    }

    // ---- details (confirmed shape) ---------------------------------------------------------------------------

    @Override
    public DomainDetails getDomainDetails(String domain, String providerOrderId) {
        Map<String, String> params = providerOrderId != null ? Map.of("order-id", providerOrderId, "options", "All") : Map.of("domain-name", domain, "options", "All");
        var res = call("GET", providerOrderId != null ? "/domains/orders/id.json" : "/domains/orders/list.json", params);
        JsonNode j = res.json();
        if (j.isMissingNode() || j.isEmpty()) throw ProviderException.notFound("No order found for " + domain);
        Instant expires = j.has("endtime") ? Instant.ofEpochSecond(j.get("endtime").asLong()) : null;
        List<String> ns = new ArrayList<>();
        JsonNode nsNode = j.path("ns");
        if (nsNode.isArray()) nsNode.forEach(n -> ns.add(n.asText()));
        return new DomainDetails(domain, j.path("orderid").asText(providerOrderId), j.path("orderstatus").asText("ACTIVE"), expires, ns, "true".equals(j.path("locked").asText()));
    }

    /** INFERRED: not a confirmed endpoint shape. */
    @Override
    public void updateNameservers(String domain, String providerOrderId, List<String> nameservers) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("order-id", providerOrderId);
        for (int i = 0; i < nameservers.size(); i++) params.put("ns" + (i + 1), nameservers.get(i));
        call("POST", "/domains/modify-ns.json", params);
    }

    /**
     * INFERRED: ResellerClub calls this "theft protection" for the registry transfer lock; the enable/disable path
     * names here are a reasonable reconstruction from general docs, not confirmed against a live sandbox - correct
     * before this ever runs against a real account. Ownership protection (unlock -> get a fresh code -> transfer
     * out) must work for real, so this is wired end to end rather than stubbed.
     */
    @Override
    public void setTransferLock(String domain, String providerOrderId, boolean locked) {
        call("POST", locked ? "/domains/enable-theft-protection.json" : "/domains/disable-theft-protection.json", Map.of("order-id", providerOrderId));
    }

    /**
     * INFERRED: {@code domains/modify-authcode.json} setting a freshly generated EPP/auth code, which the merchant can
     * then use to initiate a transfer at another registrar - the exact param name/response shape is not confirmed live.
     */
    @Override
    public String resetAuthCode(String domain, String providerOrderId) {
        String fresh = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var res = call("POST", "/domains/modify-authcode.json", Map.of("order-id", providerOrderId, "new-authcode", fresh));
        JsonNode j = res.json();
        if (j.path("status").asText("").equalsIgnoreCase("error")) throw translate(new HttpGateway.ApiException(409, HttpGateway.describe(res)));
        return fresh;
    }

    // ---- DNS zone management: least-confirmed part of this class (see class Javadoc) --------------------------

    @Override
    public List<DnsRecord> getDnsRecords(String domain) {
        var res = call("GET", "/dns/manage/search-records.json", Map.of("domain-name", domain, "type", "ALL"));
        List<DnsRecord> out = new ArrayList<>();
        JsonNode arr = res.json().path("recs").path("resrecs");
        if (arr.isArray()) for (JsonNode n : arr) out.add(new DnsRecord(n.path("id").asText(), n.path("type").asText(), n.path("host").asText(), n.path("value").asText(), n.path("ttl").asInt(3600)));
        return out;
    }

    @Override
    public DnsRecord createDnsRecord(String domain, String type, String name, String value, Integer ttlSeconds) {
        String path = switch (type) {
            case "TXT" -> "/dns/manage/add-txt-record.json";
            case "CNAME" -> "/dns/manage/add-cname-record.json";
            default -> "/dns/manage/add-ip-record.json";   // A record
        };
        Map<String, String> params = new LinkedHashMap<>();
        params.put("domain-name", domain);
        params.put("host", name);
        params.put(type.equals("A") ? "value" : "value", value);
        if (ttlSeconds != null) params.put("ttl", String.valueOf(ttlSeconds));
        var res = call("POST", path, params);
        return new DnsRecord(res.json().path("recordid").asText(java.util.UUID.randomUUID().toString()), type, name, value, ttlSeconds);
    }

    @Override
    public void updateDnsRecord(String domain, String recordId, String value, Integer ttlSeconds) {
        call("POST", "/dns/manage/update-record.json", Map.of("domain-name", domain, "record-id", recordId, "value", value, "ttl", ttlSeconds == null ? "" : String.valueOf(ttlSeconds)));
    }

    @Override
    public void deleteDnsRecord(String domain, String recordId) {
        call("POST", "/dns/manage/delete-record.json", Map.of("domain-name", domain, "record-id", recordId));
    }

    @Override
    public List<String> getSupportedTlds() {
        var res = call("GET", "/products/reseller-price.json", Map.of());
        List<String> out = new ArrayList<>();
        res.json().fieldNames().forEachRemaining(k -> { if (k.startsWith("dot")) out.add(k.substring(3)); });
        return out.isEmpty() ? List.of("com", "net", "org") : out;
    }
}

package com.platform.commerce.domains;

import com.platform.shared.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@code GET /search}: turns "what the merchant typed" into priced, time-boxed quotes. The whole endpoint is
 * gated by {@link RegistrarGate#enabled()} - until real credentials exist there is no live availability/pricing
 * answer to give, so it returns an empty result set with {@code registrarEnabled=false} rather than guessing.
 */
@Service
public class QuoteService {
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final long QUOTE_TTL_SECONDS = 300;

    private final JdbcClient jdbc;
    private final DomainPricingService pricing;
    private final RegistrarGate registrar;
    private final ProviderErrorLog errors;

    public QuoteService(JdbcClient jdbc, DomainPricingService pricing, RegistrarGate registrar, ProviderErrorLog errors) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.registrar = registrar;
        this.errors = errors;
    }

    public Map<String, Object> search(UUID tenantId, String rawQuery) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", rawQuery);
        out.put("registrarEnabled", registrar.enabled());
        if (!registrar.enabled()) { out.put("results", List.of()); return out; }
        if (rawQuery == null || rawQuery.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "A search term is required");

        String q = rawQuery.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        int slash = q.indexOf('/');
        if (slash >= 0) q = q.substring(0, slash);
        String typedTld = null, label;
        int dot = q.lastIndexOf('.');
        if (dot > 0) { label = q.substring(0, dot); typedTld = q.substring(dot + 1); } else label = q;
        if (!LABEL.matcher(label).matches()) throw BusinessException.badRequest("VALIDATION_ERROR", "Enter a valid domain name");

        var rules = pricing.activeRules();
        if (rules.isEmpty()) { out.put("results", List.of()); return out; }
        LinkedHashSet<String> tlds = new LinkedHashSet<>();
        String finalTypedTld = typedTld;
        if (typedTld != null && rules.stream().anyMatch(r -> finalTypedTld.equals(r.get("tld")))) tlds.add(typedTld);
        rules.forEach(r -> tlds.add((String) r.get("tld")));

        List<String> candidates = tlds.stream().map(t -> label + "." + t).toList();
        List<DomainProvider.AvailabilityResult> avail;
        try { avail = registrar.provider().checkAvailability(candidates); }
        catch (ProviderException e) { errors.record(null, label, "availability", e); throw toBusiness(e); }
        Map<String, DomainProvider.TldPrice> prices;
        try { prices = registrar.provider().getPricing(List.copyOf(tlds)).stream().collect(Collectors.toMap(DomainProvider.TldPrice::tld, Function.identity())); }
        catch (ProviderException e) { errors.record(null, label, "pricing", e); throw toBusiness(e); }

        Instant expiresAt = Instant.now().plusSeconds(QUOTE_TTL_SECONDS);
        List<Map<String, Object>> results = new ArrayList<>();
        for (var a : avail) {
            String tld = a.domain().substring(a.domain().lastIndexOf('.') + 1);
            var rule = rules.stream().filter(r -> tld.equals(r.get("tld"))).findFirst().orElse(null);
            var price = prices.get(tld);
            if (rule == null || price == null) continue;
            long registerPrice = DomainPricingService.applyMarkup(price.registerProviderPriceMinor(), rule);
            long renewPrice = DomainPricingService.applyMarkup(price.renewProviderPriceMinor(), rule);
            UUID quoteId = jdbc.sql("""
                    INSERT INTO commerce.domain_quotes (tenant_id, domain, tld, available, premium, price_minor, renewal_price_minor, provider_price_minor, currency, expires_at)
                    VALUES (:t, :d, :tld, :av, :pr, :p, :rp, :pp, :c, :e) RETURNING id
                    """).param("t", tenantId).param("d", a.domain()).param("tld", tld).param("av", a.available()).param("pr", a.premium())
                    .param("p", registerPrice).param("rp", renewPrice).param("pp", price.registerProviderPriceMinor()).param("c", rule.get("currency"))
                    .param("e", Timestamp.from(expiresAt)).query(UUID.class).single();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", a.domain());
            m.put("tld", tld);
            m.put("available", a.available());
            m.put("premium", a.premium());
            m.put("priceMinor", registerPrice);
            m.put("renewalPriceMinor", renewPrice);
            m.put("currency", rule.get("currency"));
            m.put("quoteId", quoteId);
            m.put("expiresAt", expiresAt.toString());
            results.add(m);
        }
        out.put("results", results);
        return out;
    }

    static BusinessException toBusiness(ProviderException e) {
        return new BusinessException(e.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST, e.code().name(), e.getMessage());
    }
}

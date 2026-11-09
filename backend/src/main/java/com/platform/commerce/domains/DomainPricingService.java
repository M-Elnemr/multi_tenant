package com.platform.commerce.domains;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-admin markup config ({@code commerce.domain_price_rules}) - the only place a domain's selling price
 * comes from. No price is ever hardcoded in Java; every quote/order re-applies {@link #applyMarkup} to whatever
 * the registrar's own {@code getPricing} call returns at that moment.
 */
@Service
public class DomainPricingService {
    private final JdbcClient jdbc;
    private final RegistrarGate registrar;

    public DomainPricingService(JdbcClient jdbc, RegistrarGate registrar) {
        this.jdbc = jdbc;
        this.registrar = registrar;
    }

    /** Active, sellable TLDs with their markup config - used by {@link QuoteService} to build the search candidate list. */
    List<Map<String, Object>> activeRules() {
        return Rows.camel(jdbc.sql("SELECT tld, markup_kind, markup_value, currency, promo_price_minor, promo_expires_at FROM commerce.domain_price_rules WHERE active ORDER BY tld").query().listOfRows());
    }

    Optional<Map<String, Object>> rule(String tld) {
        return jdbc.sql("SELECT tld, markup_kind, markup_value, currency, promo_price_minor, promo_expires_at FROM commerce.domain_price_rules WHERE tld = :t AND active")
                .param("t", tld).query().listOfRows().stream().findFirst().map(Rows::camel);
    }

    /** The registrar's own cost, with the admin's markup on top; a live (not expired) promo price overrides it outright. */
    public static long applyMarkup(long providerPriceMinor, Map<String, Object> rule) {
        Object promo = rule.get("promoPriceMinor");
        Object expires = rule.get("promoExpiresAt");
        if (promo instanceof Number p && (!(expires instanceof Instant exp) || exp.isAfter(Instant.now()))) return p.longValue();
        long value = ((Number) rule.get("markupValue")).longValue();
        return "FIXED".equals(rule.get("markupKind")) ? providerPriceMinor + value : providerPriceMinor + Math.round(providerPriceMinor * value / 100.0);
    }

    /** For the merchant search screen and the admin pricing screen. Prices stay {@code null} (never guessed) while the registrar is not configured or the lookup fails. */
    public List<Map<String, Object>> pricing() {
        var rules = Rows.camel(jdbc.sql("SELECT tld, markup_kind, markup_value, currency, promo_price_minor, promo_expires_at, active FROM commerce.domain_price_rules ORDER BY tld").query().listOfRows());
        for (var r : rules) { r.put("registerPriceMinor", null); r.put("renewPriceMinor", null); }
        if (!registrar.enabled() || rules.isEmpty()) return rules;
        List<String> tlds = rules.stream().map(r -> (String) r.get("tld")).toList();
        try {
            var live = registrar.provider().getPricing(tlds);
            for (var r : rules) {
                var p = live.stream().filter(x -> x.tld().equals(r.get("tld"))).findFirst().orElse(null);
                if (p == null) continue;
                r.put("registerPriceMinor", applyMarkup(p.registerProviderPriceMinor(), r));
                r.put("renewPriceMinor", applyMarkup(p.renewProviderPriceMinor(), r));
            }
        } catch (ProviderException ignored) { /* prices stay null; the screen shows "unavailable" rather than a guess */ }
        return rules;
    }

    public record RuleReq(String tld, String markupKind, Long markupValue, String currency, Long promoPriceMinor, Instant promoExpiresAt, Boolean active) {}

    @Transactional
    public Map<String, Object> upsert(RuleReq r) {
        if (r.tld() == null || r.tld().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "TLD is required");
        String tld = r.tld().trim().toLowerCase();
        String kind = r.markupKind() == null ? "PERCENT" : r.markupKind().toUpperCase();
        if (!Set.of("FIXED", "PERCENT").contains(kind)) throw BusinessException.badRequest("VALIDATION_ERROR", "markupKind must be FIXED or PERCENT");
        long value = r.markupValue() == null ? 0 : r.markupValue();
        if (value < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "markupValue cannot be negative");
        jdbc.sql("""
                INSERT INTO commerce.domain_price_rules (tld, markup_kind, markup_value, currency, promo_price_minor, promo_expires_at, active)
                VALUES (:tld, :k, :v, :c, :pp, :pe, :a)
                ON CONFLICT (tld) DO UPDATE SET markup_kind = :k, markup_value = :v, currency = :c, promo_price_minor = :pp, promo_expires_at = :pe, active = :a, updated_at = now()
                """).param("tld", tld).param("k", kind).param("v", value).param("c", r.currency() == null || r.currency().isBlank() ? "EGP" : r.currency())
                .param("pp", r.promoPriceMinor()).param("pe", r.promoExpiresAt() == null ? null : Timestamp.from(r.promoExpiresAt())).param("a", r.active() == null || r.active()).update();
        return Rows.camel(jdbc.sql("SELECT * FROM commerce.domain_price_rules WHERE tld = :t").param("t", tld).query().listOfRows().get(0));
    }
}

package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Country presets: currency, VAT and time zone for Egypt, Saudi Arabia and the UAE in one step. Existing prices are NOT converted: change the country before you add products, or re-price them. */
@Service
public class MarketService {
    public record Preset(String country, String currency, double vatPercent, String timezone, String nameAr, String nameEn) {}

    static final List<Preset> PRESETS = List.of(
            new Preset("EG", "EGP", 14, "Africa/Cairo", "مصر", "Egypt"),
            new Preset("SA", "SAR", 15, "Asia/Riyadh", "السعودية", "Saudi Arabia"),
            new Preset("AE", "AED", 5, "Asia/Dubai", "الإمارات", "United Arab Emirates"));

    private final JdbcClient jdbc;
    private final AuditService audit;

    public MarketService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> get(UUID t) {
        var r = jdbc.sql("SELECT default_currency, timezone FROM core.tenants WHERE id = :t").param("t", t).query().singleRow();
        var sp = jdbc.sql("SELECT vat_included, vat_percent FROM commerce.store_profiles WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst().orElse(Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", r.get("default_currency"));
        m.put("timezone", r.get("timezone"));
        m.put("vatPercent", sp.get("vat_percent"));
        m.put("vatIncluded", sp.get("vat_included"));
        m.put("country", PRESETS.stream().filter(p -> p.currency().equals(r.get("default_currency"))).map(Preset::country).findFirst().orElse(null));
        m.put("presets", PRESETS);
        long priced = jdbc.sql("SELECT count(*) FROM commerce.product_variants WHERE tenant_id = :t").param("t", t).query(Long.class).single();
        m.put("productVariants", priced);
        return m;
    }

    public Map<String, Object> apply(UUID t, UUID actor, String country) {
        Preset p = PRESETS.stream().filter(x -> x.country().equalsIgnoreCase(country == null ? "" : country)).findFirst().orElseThrow(() -> BusinessException.badRequest("VALIDATION_ERROR", "Choose Egypt, Saudi Arabia or the UAE"));
        jdbc.sql("UPDATE core.tenants SET default_currency = :c, timezone = :z, updated_at = now() WHERE id = :t").param("c", p.currency()).param("z", p.timezone()).param("t", t).update();
        jdbc.sql("UPDATE commerce.store_profiles SET vat_percent = :v, vat_included = TRUE WHERE tenant_id = :t").param("v", p.vatPercent()).param("t", t).update();
        audit.record(actor, t, "MARKET_PRESET_APPLIED", "tenant", t, "{\"country\":\"" + p.country() + "\"}");
        return get(t);
    }
}

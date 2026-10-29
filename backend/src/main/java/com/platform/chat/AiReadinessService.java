package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * How ready a shop's data is for the AI seller: the assistant only says what the shop wrote, so a product without a price, picture, options or details, or a shop without
 * delivery areas, opening hours or a returns policy, gets vague or "I'll check" answers. A score out of 100 and the fixes that matter most, for the owner's AI page.
 */
@Service
public class AiReadinessService {
    private final JdbcClient jdbc;

    public AiReadinessService(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Check(String key, int weight, boolean ok, Integer count) {}

    public Map<String, Object> readiness(UUID tenantId) {
        var p = jdbc.sql("""
                SELECT count(*) AS products,
                       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM commerce.product_media m WHERE m.product_id = p.id)) AS no_photo,
                       count(*) FILTER (WHERE coalesce(p.short_description, '') = '' AND coalesce(p.description, '') = '') AS no_description,
                       count(*) FILTER (WHERE p.specs = '[]'::jsonb) AS no_specs,
                       count(*) FILTER (WHERE p.taxonomy_id IS NULL) AS no_category,
                       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM commerce.product_variants v WHERE v.product_id = p.id AND v.price_minor > 0)) AS no_price
                FROM commerce.products p WHERE p.tenant_id = :t AND p.status = 'ACTIVE'
                """).param("t", tenantId).query().singleRow();
        long products = num(p.get("products"));
        var prof = jdbc.sql("SELECT coalesce(return_policy, '') <> '' OR coalesce(return_window_days, 0) > 0 AS returns, coalesce(shipping_policy, '') <> '' AS shipping, coalesce(working_hours::text, '{}') NOT IN ('{}', 'null') AS hours, coalesce(short_description, '') <> '' AS about FROM commerce.store_profiles WHERE tenant_id = :t")
                .param("t", tenantId).query().listOfRows().stream().findFirst().orElse(Map.of());
        long zones = jdbc.sql("SELECT count(*) FROM commerce.shipping_zones WHERE tenant_id = :t AND is_active").param("t", tenantId).query(Long.class).single();
        long branches = jdbc.sql("SELECT count(*) FROM commerce.branches WHERE tenant_id = :t AND is_active AND coalesce(address_line1, '') <> ''").param("t", tenantId).query(Long.class).single();
        long lessons = jdbc.sql("SELECT count(*) FROM chat.lessons WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single();
        List<Check> checks = List.of(
                new Check("products", 15, products > 0, (int) products),
                new Check("prices", 15, products > 0 && num(p.get("no_price")) == 0, (int) num(p.get("no_price"))),
                new Check("photos", 10, products > 0 && num(p.get("no_photo")) * 10 <= products, (int) num(p.get("no_photo"))),
                new Check("descriptions", 10, products > 0 && num(p.get("no_description")) * 10 <= products, (int) num(p.get("no_description"))),
                new Check("details", 10, products > 0 && num(p.get("no_specs")) * 4 <= products, (int) num(p.get("no_specs"))),
                new Check("categories", 5, products > 0 && num(p.get("no_category")) == 0, (int) num(p.get("no_category"))),
                new Check("delivery", 15, zones > 0, null),
                new Check("returns", 8, Boolean.TRUE.equals(prof.get("returns")), null),
                new Check("hours", 5, Boolean.TRUE.equals(prof.get("hours")), null),
                new Check("shippingText", 3, Boolean.TRUE.equals(prof.get("shipping")), null),
                new Check("address", 2, branches > 0, null),
                new Check("lessons", 2, lessons > 0, null));
        int score = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Check c : checks) {
            if (c.ok()) score += c.weight();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("ok", c.ok());
            m.put("weight", c.weight());
            if (c.count() != null) m.put("count", c.count());
            list.add(m);
        }
        list.sort((a, b) -> Boolean.compare((Boolean) a.get("ok"), (Boolean) b.get("ok")) != 0 ? Boolean.compare((Boolean) a.get("ok"), (Boolean) b.get("ok")) : Integer.compare((Integer) b.get("weight"), (Integer) a.get("weight")));
        return Map.of("score", score, "checks", list, "products", products);
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }
}

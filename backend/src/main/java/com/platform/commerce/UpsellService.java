package com.platform.commerce;

import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** "Bought together": products that real customers ordered in the same orders as this one (in stock, active). No guessing: with no history the list is empty. */
@Service
public class UpsellService {
    private final JdbcClient jdbc;

    public UpsellService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> together(UUID tenantId, String slug) {
        return Rows.camel(jdbc.sql("""
                SELECT p.id, p.name, p.slug, p.currency, count(*) AS together_orders,
                       (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS price_minor
                FROM commerce.order_items a
                JOIN commerce.products src ON src.id = a.product_id AND src.tenant_id = :t AND src.slug = :s
                JOIN commerce.orders o ON o.id = a.order_id AND o.status NOT IN ('CANCELLED','PENDING','RETURNED','REFUNDED')
                JOIN commerce.order_items b ON b.order_id = a.order_id AND b.product_id <> a.product_id
                JOIN commerce.products p ON p.id = b.product_id AND p.tenant_id = :t AND p.status = 'ACTIVE'
                WHERE EXISTS (SELECT 1 FROM commerce.inventory_items i JOIN commerce.product_variants pv ON pv.id = i.variant_id WHERE pv.product_id = p.id AND i.quantity_on_hand - i.quantity_reserved > 0)
                GROUP BY p.id, p.name, p.slug, p.currency ORDER BY count(*) DESC, p.name LIMIT 4
                """).param("t", tenantId).param("s", slug).query().listOfRows());
    }
}

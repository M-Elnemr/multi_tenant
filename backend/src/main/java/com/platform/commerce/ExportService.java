package com.platform.commerce;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** CSV downloads (UTF-8 with a BOM so Excel shows Arabic correctly). Cells that start with = + - @ are prefixed so a spreadsheet never runs them as formulas. */
@Service
public class ExportService {
    private final JdbcClient jdbc;

    public ExportService(JdbcClient jdbc) { this.jdbc = jdbc; }

    static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v).replace("\r", " ").replace("\n", " ");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String csv(List<String> head, List<Map<String, Object>> rows, List<String> keys) {
        StringBuilder b = new StringBuilder("﻿").append(String.join(",", head)).append("\n");
        for (var r : rows) { for (int i = 0; i < keys.size(); i++) { if (i > 0) b.append(','); b.append(cell(r.get(keys.get(i)))); } b.append("\n"); }
        return b.toString();
    }

    public String orders(UUID t, LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now().minusDays(90) : from, e = to == null ? LocalDate.now() : to;
        var rows = jdbc.sql("""
                SELECT order_number, created_at, status, payment_status, payment_method, source, currency, subtotal_minor / 100.0 AS subtotal, discount_minor / 100.0 AS discount, shipping_minor / 100.0 AS shipping,
                       total_minor / 100.0 AS total, customer_name_snapshot, customer_phone_snapshot, governorate_code, area, tracking_number, risk_level
                FROM commerce.orders WHERE tenant_id = :t AND created_at >= :f AND created_at < :e ORDER BY created_at DESC LIMIT 50000
                """).param("t", t).param("f", java.sql.Date.valueOf(f)).param("e", java.sql.Date.valueOf(e.plusDays(1))).query().listOfRows();
        return csv(List.of("order_number", "created_at", "status", "payment_status", "payment_method", "source", "currency", "subtotal", "discount", "shipping", "total", "customer", "phone", "governorate", "area", "tracking", "risk"),
                rows, List.of("order_number", "created_at", "status", "payment_status", "payment_method", "source", "currency", "subtotal", "discount", "shipping", "total", "customer_name_snapshot", "customer_phone_snapshot", "governorate_code", "area", "tracking_number", "risk_level"));
    }

    public String customers(UUID t) {
        var rows = jdbc.sql("""
                SELECT customer_phone_snapshot AS phone, max(customer_name_snapshot) AS name, count(*) FILTER (WHERE status NOT IN ('CANCELLED','PENDING')) AS orders,
                       count(*) FILTER (WHERE status = 'ARRIVED') AS delivered, coalesce(sum(total_minor) FILTER (WHERE status NOT IN ('CANCELLED','RETURNED','REFUNDED','PENDING')), 0) / 100.0 AS spent,
                       min(created_at) AS first_order, max(created_at) AS last_order, max(whatsapp_number) FILTER (WHERE whatsapp_opt_in_at IS NOT NULL) AS whatsapp
                FROM commerce.orders WHERE tenant_id = :t AND customer_phone_snapshot <> '' GROUP BY customer_phone_snapshot ORDER BY spent DESC LIMIT 50000
                """).param("t", t).query().listOfRows();
        return csv(List.of("phone", "name", "orders", "delivered", "spent", "first_order", "last_order", "whatsapp_opt_in"), rows, List.of("phone", "name", "orders", "delivered", "spent", "first_order", "last_order", "whatsapp"));
    }
}

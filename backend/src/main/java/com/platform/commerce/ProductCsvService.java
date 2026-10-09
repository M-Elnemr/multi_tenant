package com.platform.commerce;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Spreadsheet import/export for products: one row per product (single variant). Categories are written as "Men > Shirts" and created when missing. */
@Service
public class ProductCsvService {
    static final List<String> HEADER = List.of("name", "brand", "category", "sku", "price", "compare_at_price", "stock", "description", "status");
    private static final int MAX_ROWS = 500;

    private final JdbcClient jdbc;
    private final CatalogService catalog;

    public ProductCsvService(JdbcClient jdbc, CatalogService catalog) { this.jdbc = jdbc; this.catalog = catalog; }

    public String export(UUID tenantId) {
        var rows = jdbc.sql("""
                SELECT p.name, p.brand, p.status, p.description,
                  (SELECT string_agg(c.name, ' > ') FROM (WITH RECURSIVE up AS (SELECT id, parent_id, name, 0 AS d FROM commerce.categories WHERE id = p.category_id
                      UNION ALL SELECT c2.id, c2.parent_id, c2.name, up.d + 1 FROM commerce.categories c2 JOIN up ON c2.id = up.parent_id) SELECT name FROM up ORDER BY d DESC) c) AS category,
                  v.sku, v.price_minor, v.compare_at_price_minor,
                  (SELECT coalesce(sum(i.quantity_on_hand), 0) FROM commerce.inventory_items i WHERE i.variant_id = v.id) AS stock
                FROM commerce.products p JOIN commerce.product_variants v ON v.product_id = p.id AND v.status = 'ACTIVE'
                WHERE p.tenant_id = :t AND p.status <> 'ARCHIVED' ORDER BY p.created_at, v.created_at
                """).param("t", tenantId).query().listOfRows();
        StringBuilder sb = new StringBuilder("﻿").append(String.join(",", HEADER)).append("\r\n");   // BOM so Excel opens Arabic text correctly
        for (var r : rows) {
            sb.append(cell(r.get("name"))).append(',').append(cell(r.get("brand"))).append(',').append(cell(r.get("category"))).append(',').append(cell(r.get("sku"))).append(',')
              .append(money(r.get("price_minor"))).append(',').append(money(r.get("compare_at_price_minor"))).append(',').append(r.get("stock")).append(',')
              .append(cell(r.get("description"))).append(',').append(cell(r.get("status"))).append("\r\n");
        }
        return sb.toString();
    }

    public record RowError(int row, String message) {}

    public Map<String, Object> importCsv(UUID tenantId, UUID actor, String csv, UUID branchId) {
        if (csv == null || csv.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "The file is empty");
        List<List<String>> table = parse(csv.startsWith("﻿") ? csv.substring(1) : csv);
        if (table.size() < 2) throw BusinessException.badRequest("VALIDATION_ERROR", "The file has no product rows");
        if (table.size() - 1 > MAX_ROWS) throw BusinessException.badRequest("TOO_MANY_ROWS", "Up to " + MAX_ROWS + " products per file");
        List<String> head = table.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        if (!head.contains("name") || !head.contains("price")) throw BusinessException.badRequest("VALIDATION_ERROR", "The file needs at least the columns: name, price");
        UUID branch = branchId != null ? branchId : jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", tenantId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("NO_BRANCH", "Add a branch first"));
        Map<String, UUID> catCache = new HashMap<>();
        int created = 0;
        List<RowError> errors = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> r = table.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            Map<String, String> m = new HashMap<>();
            for (int c = 0; c < head.size() && c < r.size(); c++) m.put(head.get(c), r.get(c).trim());
            try {
                String name = m.getOrDefault("name", "");
                if (name.isEmpty()) throw new IllegalArgumentException("Missing name");
                long price = toMinor(m.get("price"));
                Long compare = m.get("compare_at_price") == null || m.get("compare_at_price").isEmpty() ? null : toMinor(m.get("compare_at_price"));
                int stock = m.get("stock") == null || m.get("stock").isEmpty() ? 0 : Integer.parseInt(m.get("stock").replaceAll("[^0-9-]", ""));
                String sku = m.getOrDefault("sku", "").isEmpty() ? "IMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() : m.get("sku");
                String status = "DRAFT".equalsIgnoreCase(m.get("status")) ? "DRAFT" : "ACTIVE";
                UUID categoryId = categoryPath(tenantId, m.getOrDefault("category", ""), catCache);
                catalog.createProduct(tenantId, actor, new CatalogService.ProductReq(name, m.getOrDefault("description", ""), null, categoryId, m.getOrDefault("brand", "").isEmpty() ? null : m.get("brand"), status, List.of(),
                        List.of(new CatalogService.VariantReq(sku, price, compare, null, null, Map.of(), stock > 0 ? List.of(new CatalogService.StockReq(branch, stock)) : List.of())), List.of()));
                created++;
            } catch (BusinessException e) {
                errors.add(new RowError(i + 1, e.getMessage()));
            } catch (RuntimeException e) {
                errors.add(new RowError(i + 1, e.getMessage() == null ? "Invalid row" : e.getMessage()));
            }
        }
        return Map.of("created", created, "errors", Rows.camel(errors.stream().map(e -> Map.<String, Object>of("row", e.row(), "message", e.message())).toList()));
    }

    private UUID categoryPath(UUID tenantId, String path, Map<String, UUID> cache) {
        if (path == null || path.isBlank()) return null;
        UUID parent = null;
        StringBuilder key = new StringBuilder();
        for (String part : path.split(">")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            key.append('>').append(name.toLowerCase(Locale.ROOT));
            UUID id = cache.get(key.toString());
            if (id == null) {
                id = jdbc.sql("SELECT id FROM commerce.categories WHERE tenant_id = :t AND lower(name) = lower(:n) AND parent_id IS NOT DISTINCT FROM CAST(:p AS uuid) LIMIT 1")
                        .param("t", tenantId).param("n", name).param("p", parent).query(UUID.class).optional().orElse(null);
                if (id == null) id = (UUID) catalog.createCategory(tenantId, name, parent, null, 0).get("id");
                cache.put(key.toString(), id);
            }
            parent = id;
        }
        return parent;
    }

    static long toMinor(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Missing price");
        String n = s.trim().replace(',', '.').replaceAll("[^0-9.]", "");
        if (n.isEmpty()) throw new IllegalArgumentException("Invalid price: " + s);
        return Math.round(Double.parseDouble(n) * 100);
    }

    private static String money(Object minor) { return minor == null ? "" : String.format(Locale.ROOT, "%.2f", ((Number) minor).longValue() / 100.0); }

    private static String cell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        // neutralise spreadsheet formulas (=, +, -, @) so an exported name can never run as one when opened in Excel
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** RFC 4180 style parser; the delimiter is a comma, or a semicolon when the header line uses one (common in Arabic-locale Excel). */
    static List<List<String>> parse(String text) {
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        char delim = firstLine.chars().filter(c -> c == ';').count() > firstLine.chars().filter(c -> c == ',').count() ? ';' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; } else quoted = false; }
                else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == delim) { row.add(cur.toString()); cur.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cur.append(ch);
        }
        if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); rows.add(row); }
        return rows;
    }
}

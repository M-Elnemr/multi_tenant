package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tax invoices and credit notes. Numbers run without gaps per shop and year (INV-2026-000001, CN-2026-000001). A document is a snapshot taken when it is issued:
 * the seller, the buyer, the lines and the VAT split never change afterwards, even if the order, the product or the shop details do.
 */
@Service
public class InvoiceService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public InvoiceService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private String number(UUID t, String kind, String prefix) {
        int year = LocalDate.now().getYear();
        int seq = jdbc.sql("INSERT INTO commerce.counters (tenant_id, kind, year, seq) VALUES (:t, :k, :y, 1) ON CONFLICT (tenant_id, kind, year) DO UPDATE SET seq = commerce.counters.seq + 1 RETURNING seq").param("t", t).param("k", kind).param("y", year).query(Integer.class).single();
        return prefix + "-" + year + "-" + String.format("%06d", seq);
    }

    private Map<String, Object> order(UUID t, UUID orderId) {
        return jdbc.sql("SELECT * FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
    }

    /** The invoice of an order, issued on the first request and the same document ever after. */
    @Transactional
    public Map<String, Object> issue(UUID t, UUID actor, UUID orderId) {
        var existing = jdbc.sql("SELECT id FROM commerce.invoices WHERE order_id = :o AND kind = 'INVOICE'").param("o", orderId).query(UUID.class).optional();
        if (existing.isPresent()) return get(t, existing.get());
        var o = order(t, orderId);
        if (List.of("PENDING", "CANCELLED").contains(o.get("status"))) throw BusinessException.conflict("INVALID_STATUS", "An invoice is issued for orders that were not cancelled");
        var sp = jdbc.sql("SELECT coalesce(nullif(store_name, ''), '') AS name, coalesce(tax_id, '') AS tax_id, coalesce(address_text, '') AS address, vat_included, vat_percent FROM commerce.store_profiles WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst().orElse(Map.of());
        String seller = (String) sp.getOrDefault("name", "");
        if (seller.isBlank()) seller = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single();
        boolean included = !Boolean.FALSE.equals(sp.get("vat_included"));
        double pct = sp.get("vat_percent") instanceof Number n ? n.doubleValue() : 0;
        long total = ((Number) o.get("total_minor")).longValue();
        long vat = pct > 0 && included ? Math.round(total * pct / (100 + pct)) : 0;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var i : jdbc.sql("SELECT product_name_snapshot, variant_name_snapshot, sku_snapshot, quantity, unit_price_minor, line_total_minor, note FROM commerce.order_items WHERE order_id = :o ORDER BY created_at").param("o", orderId).query().listOfRows()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("name", i.get("product_name_snapshot")); l.put("variant", i.get("variant_name_snapshot")); l.put("sku", i.get("sku_snapshot")); l.put("quantity", i.get("quantity")); l.put("unitMinor", i.get("unit_price_minor")); l.put("totalMinor", i.get("line_total_minor"));
            if (i.get("note") != null) l.put("note", i.get("note"));
            lines.add(l);
        }
        String addr = buyerAddress(o);
        UUID id = jdbc.sql("""
                INSERT INTO commerce.invoices (tenant_id, number, kind, order_id, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, vat_percent, vat_minor, vat_included, seller_name, seller_tax_id, seller_address, buyer_name, buyer_phone, buyer_address, lines, created_by)
                VALUES (:t, :n, 'INVOICE', :o, :cur, :sub, :disc, :ship, :total, :pct, :vat, :inc, :sn, :st, :sa, :bn, :bp, :ba, CAST(:lines AS jsonb), :u) RETURNING id
                """).param("t", t).param("n", number(t, "INV", "INV")).param("o", orderId).param("cur", o.get("currency")).param("sub", o.get("subtotal_minor")).param("disc", o.get("discount_minor")).param("ship", (((Number) o.get("shipping_minor")).longValue()) + ((Number) o.getOrDefault("cod_fee_minor", 0L)).longValue())
                .param("total", total).param("pct", pct).param("vat", vat).param("inc", included).param("sn", seller).param("st", sp.getOrDefault("tax_id", "")).param("sa", sp.getOrDefault("address", ""))
                .param("bn", o.get("customer_name_snapshot")).param("bp", o.get("customer_phone_snapshot")).param("ba", addr).param("lines", json(lines)).param("u", actor).query(UUID.class).single();
        audit.record(actor, t, "INVOICE_ISSUED", "invoice", id, null);
        return get(t, id);
    }

    /** A credit note cancels the whole invoice of an order that was returned, refunded or cancelled after the invoice was issued. */
    @Transactional
    public Map<String, Object> creditNote(UUID t, UUID actor, UUID orderId, String reason) {
        var o = order(t, orderId);
        if (!List.of("RETURNED", "REFUNDED", "CANCELLED").contains(o.get("status"))) throw BusinessException.conflict("INVALID_STATUS", "A credit note is for orders that were returned, refunded or cancelled");
        var inv = jdbc.sql("SELECT * FROM commerce.invoices WHERE order_id = :o AND kind = 'INVOICE'").param("o", orderId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.conflict("NO_INVOICE", "Issue the invoice first: a credit note refers to it"));
        var existing = jdbc.sql("SELECT id FROM commerce.invoices WHERE order_id = :o AND kind = 'CREDIT_NOTE'").param("o", orderId).query(UUID.class).optional();
        if (existing.isPresent()) return get(t, existing.get());
        UUID id = jdbc.sql("""
                INSERT INTO commerce.invoices (tenant_id, number, kind, order_id, related_invoice_id, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, vat_percent, vat_minor, vat_included, seller_name, seller_tax_id, seller_address, buyer_name, buyer_phone, buyer_address, lines, reason, created_by)
                SELECT tenant_id, :n, 'CREDIT_NOTE', order_id, id, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, vat_percent, vat_minor, vat_included, seller_name, seller_tax_id, seller_address, buyer_name, buyer_phone, buyer_address, lines, :r, :u
                FROM commerce.invoices WHERE id = :i RETURNING id
                """).param("n", number(t, "CN", "CN")).param("r", reason == null || reason.isBlank() ? null : reason.trim()).param("u", actor).param("i", inv.get("id")).query(UUID.class).single();
        audit.record(actor, t, "CREDIT_NOTE_ISSUED", "invoice", id, null);
        return get(t, id);
    }

    /**
     * The credit note of one return: only the pieces that came back, at the price they were sold for. The order's invoice is issued first when it does not exist yet
     * (a counter sale never asked for one), because a credit note always refers to an invoice.
     */
    @Transactional
    public Map<String, Object> creditNoteForReturn(UUID t, UUID actor, UUID orderId, UUID returnId, List<Map<String, Object>> lines, long amountMinor, String reason) {
        var inv = jdbc.sql("SELECT * FROM commerce.invoices WHERE order_id = :o AND kind = 'INVOICE'").param("o", orderId).query().listOfRows().stream().findFirst().orElse(null);
        if (inv == null) { issue(t, actor, orderId); inv = jdbc.sql("SELECT * FROM commerce.invoices WHERE order_id = :o AND kind = 'INVOICE'").param("o", orderId).query().listOfRows().stream().findFirst().orElseThrow(); }
        double pct = inv.get("vat_percent") instanceof Number n ? n.doubleValue() : 0;
        boolean included = !Boolean.FALSE.equals(inv.get("vat_included"));
        long vat = pct > 0 && included ? Math.round(amountMinor * pct / (100 + pct)) : 0;
        UUID id = jdbc.sql("""
                INSERT INTO commerce.invoices (tenant_id, number, kind, order_id, related_invoice_id, return_id, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, vat_percent, vat_minor, vat_included, seller_name, seller_tax_id, seller_address, buyer_name, buyer_phone, buyer_address, lines, reason, created_by)
                SELECT tenant_id, :n, 'CREDIT_NOTE', order_id, id, :rid, currency, :amt, 0, 0, :amt, vat_percent, :vat, vat_included, seller_name, seller_tax_id, seller_address, buyer_name, buyer_phone, buyer_address, CAST(:lines AS jsonb), :r, :u
                FROM commerce.invoices WHERE id = :i RETURNING id
                """).param("n", number(t, "CN", "CN")).param("rid", returnId).param("amt", amountMinor).param("vat", vat).param("lines", json(lines))
                .param("r", reason).param("u", actor).param("i", inv.get("id")).query(UUID.class).single();
        audit.record(actor, t, "CREDIT_NOTE_ISSUED", "invoice", id, null);
        return get(t, id);
    }

    @SuppressWarnings("unchecked")
    private static String buyerAddress(Map<String, Object> o) {
        StringBuilder b = new StringBuilder();
        try {
            Object snap = o.get("shipping_address_snapshot");
            if (snap != null) {
                Map<String, Object> m = com.platform.shared.Rows.json(snap.toString());
                for (String k : new String[]{"addressLine1", "area", "city"}) if (m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) { if (b.length() > 0) b.append(", "); b.append(m.get(k)); }
            }
        } catch (RuntimeException ignored) { /* an address is optional on an invoice */ }
        if (b.length() == 0 && o.get("area") != null) b.append(o.get("area"));
        return b.length() > 390 ? b.substring(0, 390) : b.toString();
    }

    private static String json(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> get(UUID t, UUID id) {
        var r = jdbc.sql("SELECT i.*, i.lines::text AS lines_text, o.order_number FROM commerce.invoices i JOIN commerce.orders o ON o.id = i.order_id WHERE i.id = :i AND i.tenant_id = :t").param("i", id).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Invoice not found"));
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        m.remove("lines");
        m.remove("linesText");
        m.put("lines", Rows.jsonList(r.get("lines_text")));
        return m;
    }

    public List<Map<String, Object>> forOrder(UUID t, UUID orderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : jdbc.sql("SELECT id FROM commerce.invoices WHERE tenant_id = :t AND order_id = :o ORDER BY issued_at").param("t", t).param("o", orderId).query(UUID.class).list()) out.add(get(t, id));
        return out;
    }

    public Map<String, Object> list(UUID t, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.invoices WHERE tenant_id = :t").param("t", t).query(Long.class).single();
        var rows = jdbc.sql("SELECT i.id, i.number, i.kind, i.issued_at, i.total_minor, i.vat_minor, i.currency, i.buyer_name, o.order_number FROM commerce.invoices i JOIN commerce.orders o ON o.id = i.order_id WHERE i.tenant_id = :t ORDER BY i.issued_at DESC LIMIT :l OFFSET :f")
                .param("t", t).param("l", page.pageSize()).param("f", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }
}

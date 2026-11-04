package com.platform.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a till terminal keeps on the counter, and what it sends back. The terminal works first on its own copy (so the shop sells with no internet) and queues every action
 * with an id it made itself; {@link #push} applies them in the terminal's order, each once (a resend is answered from {@code pos_ops}), each in its own transaction.
 * A sale that was paid at the counter is never refused here: what does not add up becomes a sync issue the manager sees and fixes.
 */
@Service
public class PosSyncService {
    private static final Logger log = LoggerFactory.getLogger(PosSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> TYPES = Set.of("SHIFT_OPEN", "CASH_MOVE", "SALE", "RETURN", "SHIFT_CLOSE", "COUNT");
    private static final Set<String> CASH_KINDS = Set.of("PAY_IN", "PAY_OUT", "DROP");

    /** One action from the terminal; {@code at} is the terminal's clock. */
    public record Op(UUID opId, long seq, String type, String at, UUID cashierId, Map<String, Object> payload) {}

    private final JdbcClient jdbc;
    private final PosDeviceService devices;
    private final InventoryService inventory;
    private final StoreCustomerService customers;
    private final LoyaltyService loyalty;
    private final ReturnService returns;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PosSyncService(JdbcClient jdbc, PosDeviceService devices, InventoryService inventory, StoreCustomerService customers, LoyaltyService loyalty, ReturnService returns,
                          AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.inventory = inventory;
        this.customers = customers;
        this.loyalty = loyalty;
        this.returns = returns;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ---- what the terminal keeps -----------------------------------------------------------------------------------------

    /**
     * Everything the terminal needs to sell with no internet. {@code since} (the {@code serverTime} of the last call) sends only the products changed after it; without it, the
     * whole branch catalog (also done by the terminal now and then, so deleted products leave its copy).
     */
    public Map<String, Object> bootstrap(PosDeviceService.Device d, String since) {
        OffsetDateTime from = null;
        if (since != null && !since.isBlank()) try { from = OffsetDateTime.parse(since).minusMinutes(2); } catch (DateTimeParseException e) { from = null; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverTime", OffsetDateTime.now().toString());
        out.put("full", from == null);
        Map<String, Object> dev = new LinkedHashMap<>(devices.device(d.tenantId(), d.id()));
        dev.put("lastSeq", jdbc.sql("SELECT last_seq FROM commerce.pos_devices WHERE id = :i").param("i", d.id()).query(Long.class).single());
        dev.put("lastInvoice", lastInvoiceNumber(d));
        out.put("device", dev);
        out.put("shop", shop(d.tenantId()));
        out.put("methods", PosService.METHODS.stream().sorted(Comparator.comparing(m -> List.of("CASH", "CARD_POS", "TRANSFER").indexOf(m))).toList());
        out.put("cashiers", devices.cashiers(d.tenantId()));
        out.put("catalog", catalog(d, from));
        out.put("invoices", recentInvoices(d, from));
        out.put("openShift", jdbc.sql("SELECT id, cashier_id, opened_at, opening_float_minor FROM commerce.pos_shifts WHERE device_id = :d AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1")
                .param("d", d.id()).query().listOfRows().stream().findFirst().map(Rows::camel).orElse(null));
        out.put("counts", Rows.camel(jdbc.sql("SELECT id, name, status, created_at FROM commerce.stock_counts WHERE tenant_id = :t AND branch_id = :b AND status IN ('COUNTING', 'REVIEW') ORDER BY created_at DESC LIMIT 20")
                .param("t", d.tenantId()).param("b", d.branchId()).query().listOfRows()));
        return out;
    }

    private Map<String, Object> shop(UUID t) {
        var sp = jdbc.sql("""
                SELECT coalesce(nullif(sp.store_name, ''), tn.name) AS name, coalesce(sp.address_text, '') AS address, coalesce(sp.tax_id, '') AS tax_id, coalesce(sp.support_phone, '') AS phone,
                       sp.vat_included, sp.vat_percent, sp.receipt_size, coalesce(sp.receipt_footer, '') AS receipt_footer, sp.pos_max_discount_pct, tn.timezone
                FROM core.tenants tn LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = tn.id WHERE tn.id = :t
                """).param("t", t).query().listOfRows().stream().findFirst().orElse(Map.of());
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(sp));
        m.putIfAbsent("receiptSize", "80");
        m.put("currency", jdbc.sql("SELECT coalesce((SELECT currency FROM commerce.product_variants WHERE tenant_id = :t LIMIT 1), 'EGP')").param("t", t).query(String.class).single());
        return m;
    }

    private List<Map<String, Object>> catalog(PosDeviceService.Device d, OffsetDateTime from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT v.id AS variant_id, p.id AS product_id, p.name, v.combo_key, v.sku, v.barcode, v.price_minor, v.currency,
                       (v.status = 'ACTIVE' AND p.status = 'ACTIVE') AS active,
                       coalesce(i.quantity_on_hand - i.quantity_reserved, 0) AS stock,
                       coalesce(tx.name_ar, '') AS category_ar, coalesce(tx.name_en, '') AS category_en,
                       CAST(NULL AS varchar) AS image   -- the till shows no pictures (simpler screen, more products on it, a lighter download)
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id
                LEFT JOIN commerce.inventory_items i ON i.variant_id = v.id AND i.branch_id = :b
                LEFT JOIN commerce.taxonomy tx ON tx.id = p.taxonomy_id
                WHERE v.tenant_id = :t AND (CAST(:f AS timestamptz) IS NULL AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                       OR CAST(:f AS timestamptz) IS NOT NULL AND (v.updated_at > CAST(:f AS timestamptz) OR p.updated_at > CAST(:f AS timestamptz) OR i.updated_at > CAST(:f AS timestamptz)))
                ORDER BY p.name, v.sku LIMIT 20000
                """).param("t", d.tenantId()).param("b", d.branchId()).param("f", from).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            String combo = (String) r.get("combo_key");
            m.put("label", combo == null || combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": "));
            m.remove("comboKey");
            out.add(m);
        }
        return out;
    }

    /** The branch's till sales of the last 30 days with what may still be returned of each line, so a return works with no internet. */
    private List<Map<String, Object>> recentInvoices(PosDeviceService.Device d, OffsetDateTime from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT o.id, o.order_number, o.created_at, o.client_created_at, o.total_minor, o.discount_minor, o.currency, o.status, o.customer_name_snapshot, o.customer_phone_snapshot, o.cashier_id,
                       (SELECT json_agg(json_build_object('variantId', i.variant_id, 'name', i.product_name_snapshot, 'label', i.variant_name_snapshot, 'sku', i.sku_snapshot, 'qty', i.quantity,
                               'unitMinor', round(i.line_total_minor::numeric / i.quantity), 'returned', coalesce((SELECT sum(ri.quantity) FROM commerce.order_return_items ri WHERE ri.order_item_id = i.id), 0)) ORDER BY i.created_at)
                        FROM commerce.order_items i WHERE i.order_id = o.id)::text AS items,
                       (SELECT json_agg(json_build_object('method', p.method, 'amountMinor', p.amount_minor))
                        FROM commerce.order_payments p WHERE p.order_id = o.id AND p.kind = 'PAYMENT')::text AS payments
                FROM commerce.orders o
                WHERE o.tenant_id = :t AND o.source = 'POS' AND o.created_at > now() - interval '30 days'
                  AND (o.pos_device_id IS NULL OR o.pos_device_id IN (SELECT id FROM commerce.pos_devices WHERE branch_id = :b))
                  AND (CAST(:f AS timestamptz) IS NULL OR o.updated_at > CAST(:f AS timestamptz))
                ORDER BY o.created_at DESC LIMIT 1500
                """).param("t", d.tenantId()).param("b", d.branchId()).param("f", from).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            try {
                m.put("items", r.get("items") == null ? List.of() : JSON.readValue((String) r.get("items"), List.class));
                m.put("payments", r.get("payments") == null ? List.of() : JSON.readValue((String) r.get("payments"), List.class));
            } catch (Exception e) { m.put("items", List.of()); m.put("payments", List.of()); }
            out.add(m);
        }
        return out;
    }

    /** The highest invoice sequence this terminal's code has used, so a terminal set up again (its browser data cleared) never repeats a number. */
    private long lastInvoiceNumber(PosDeviceService.Device d) {
        String prefix = "POS-" + d.code() + "-";
        return jdbc.sql("SELECT coalesce(max(CAST(substring(order_number FROM :re) AS bigint)), 0) FROM commerce.orders WHERE tenant_id = :t AND order_number LIKE :p")
                .param("re", "^" + java.util.regex.Pattern.quote(prefix) + "([0-9]{1,12})$").param("t", d.tenantId()).param("p", prefix + "%").query(Long.class).single();
    }

    // ---- what the terminal sends -----------------------------------------------------------------------------------------

    /**
     * Applies the terminal's actions in its order. Each answer: APPLIED, FLAGGED (applied, with something for the manager), FAILED (could not be applied, kept and shown as an
     * issue: sending it again changes nothing) or RETRY (a passing problem on our side: the terminal sends it again later, and nothing after it is applied before it).
     */
    public Map<String, Object> push(PosDeviceService.Device d, List<Op> ops) {
        if (ops == null) ops = List.of();
        if (ops.size() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "At most 200 actions at a time");
        List<Op> sorted = new ArrayList<>(ops);
        sorted.sort(Comparator.comparingLong(Op::seq));
        List<Map<String, Object>> results = new ArrayList<>();
        boolean stop = false;
        for (Op op : sorted) {
            if (stop) { results.add(Map.of("opId", String.valueOf(op.opId()), "status", "RETRY")); continue; }
            if (op.opId() == null || op.type() == null || !TYPES.contains(op.type())) {
                results.add(Map.of("opId", String.valueOf(op.opId()), "status", "FAILED", "error", "Unknown action"));
                continue;
            }
            var done = jdbc.sql("SELECT status, result::text AS result, error FROM commerce.pos_ops WHERE op_id = :o AND tenant_id = :t").param("o", op.opId()).param("t", d.tenantId()).query().listOfRows();
            if (!done.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>(Map.of("opId", op.opId().toString(), "status", done.get(0).get("status"), "duplicate", true));
                if (done.get(0).get("error") != null) r.put("error", done.get(0).get("error"));
                results.add(r);
                continue;
            }
            try {
                Map<String, Object> r = tx.execute(s -> applyOne(d, op));
                results.add(r);
            } catch (BusinessException e) {
                // the action itself cannot be applied (a shift that does not exist, a bad amount...): kept as failed and shown to the manager, never retried
                tx.executeWithoutResult(s -> {
                    jdbc.sql("INSERT INTO commerce.pos_ops (op_id, tenant_id, device_id, seq, type, client_at, status, error) VALUES (:o, :t, :d, :s, :ty, :at, 'FAILED', :e) ON CONFLICT (op_id) DO NOTHING")
                            .param("o", op.opId()).param("t", d.tenantId()).param("d", d.id()).param("s", op.seq()).param("ty", op.type()).param("at", time(op.at())).param("e", e.getMessage()).update();
                    issue(d, op.opId(), "FAILED", Map.of("type", op.type(), "error", String.valueOf(e.getMessage()), "payload", op.payload() == null ? Map.of() : op.payload()));
                });
                results.add(Map.of("opId", op.opId().toString(), "status", "FAILED", "error", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                log.warn("POS sync: action {} {} from terminal {} not applied yet: {}", op.type(), op.opId(), d.code(), e.toString());
                results.add(Map.of("opId", op.opId().toString(), "status", "RETRY"));
                stop = true;   // keep the order: what comes after waits for this one
            }
        }
        long top = sorted.stream().mapToLong(Op::seq).max().orElse(0);
        if (top > 0) jdbc.sql("UPDATE commerce.pos_devices SET last_seq = greatest(last_seq, :s), last_seen_at = now() WHERE id = :i").param("s", top).param("i", d.id()).update();
        return Map.of("results", results, "serverTime", OffsetDateTime.now().toString());
    }

    private Map<String, Object> applyOne(PosDeviceService.Device d, Op op) {
        Map<String, Object> p = op.payload() == null ? Map.of() : op.payload();
        List<Map<String, Object>> flags = new ArrayList<>();
        Map<String, Object> result = switch (op.type()) {
            case "SHIFT_OPEN" -> shiftOpen(d, op, p);
            case "CASH_MOVE" -> cashMove(d, op, p);
            case "SALE" -> sale(d, op, p, flags);
            case "RETURN" -> giveBack(d, op, p, flags);
            case "SHIFT_CLOSE" -> shiftClose(d, op, p, flags);
            case "COUNT" -> count(d, op, p);
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown action");
        };
        OffsetDateTime at = time(op.at());
        if (at != null && Math.abs(java.time.Duration.between(at, OffsetDateTime.now()).toMinutes()) > 10 && "SALE".equals(op.type()) && op.seq() > 0
                && jdbc.sql("SELECT count(*) FROM commerce.pos_sync_issues WHERE device_id = :d AND kind = 'CLOCK' AND resolved_at IS NULL").param("d", d.id()).query(Long.class).single() == 0
                && at.isAfter(OffsetDateTime.now().plusMinutes(10)))
            flags.add(Map.of("kind", "CLOCK", "terminalTime", op.at(), "serverTime", OffsetDateTime.now().toString()));   // a clock in the future is wrong; one far in the past is just a sale sent late
        for (var f : flags) {
            Map<String, Object> detail = new LinkedHashMap<>(f);
            String kind = (String) detail.remove("kind");
            issue(d, op.opId(), kind, detail);
        }
        String status = flags.isEmpty() ? "APPLIED" : "FLAGGED";
        jdbc.sql("INSERT INTO commerce.pos_ops (op_id, tenant_id, device_id, seq, type, client_at, status, result) VALUES (:o, :t, :d, :s, :ty, :at, :st, CAST(:r AS jsonb))")
                .param("o", op.opId()).param("t", d.tenantId()).param("d", d.id()).param("s", op.seq()).param("ty", op.type()).param("at", at).param("st", status).param("r", json(result)).update();
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("opId", op.opId().toString());
        out.put("status", status);
        if (!flags.isEmpty()) out.put("flags", flags.stream().map(f -> f.get("kind")).toList());
        return out;
    }

    private void issue(PosDeviceService.Device d, UUID opId, String kind, Map<String, Object> detail) {
        jdbc.sql("INSERT INTO commerce.pos_sync_issues (tenant_id, op_id, device_id, kind, detail) VALUES (:t, :o, :d, :k, CAST(:j AS jsonb))")
                .param("t", d.tenantId()).param("o", opId).param("d", d.id()).param("k", kind).param("j", json(detail)).update();
    }

    // ---- shifts and the drawer ------------------------------------------------------------------------------------------

    private Map<String, Object> shiftOpen(PosDeviceService.Device d, Op op, Map<String, Object> p) {
        UUID id = uuid(p.get("shiftId"), "shiftId");
        UUID cashier = cashier(d, op);
        long opening = amount(p.get("openingFloatMinor"), true);
        jdbc.sql("""
                INSERT INTO commerce.pos_shifts (id, tenant_id, branch_id, device_id, cashier_id, opened_at, opening_float_minor, currency) VALUES (:i, :t, :b, :d, :c, :at, :f, :cur)
                ON CONFLICT (id) DO NOTHING
                """).param("i", id).param("t", d.tenantId()).param("b", d.branchId()).param("d", d.id()).param("c", cashier).param("at", timeOrNow(op.at())).param("f", opening)
                .param("cur", String.valueOf(shop(d.tenantId()).get("currency"))).update();
        audit.record(cashier, d.tenantId(), "POS_SHIFT_OPENED", "pos_shift", id, null);
        return Map.of("shiftId", id.toString());
    }

    private Map<String, Object> cashMove(PosDeviceService.Device d, Op op, Map<String, Object> p) {
        UUID id = uuid(p.get("moveId"), "moveId");
        UUID shift = shiftOf(d, p.get("shiftId"));
        String kind = String.valueOf(p.get("kind"));
        if (!CASH_KINDS.contains(kind)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown cash movement");
        long amount = amount(p.get("amountMinor"), false);
        String reason = text(p.get("reason"), 200);
        jdbc.sql("INSERT INTO commerce.pos_cash_moves (id, tenant_id, shift_id, kind, amount_minor, reason, at, user_id) VALUES (:i, :t, :s, :k, :a, :r, :at, :u) ON CONFLICT (id) DO NOTHING")
                .param("i", id).param("t", d.tenantId()).param("s", shift).param("k", kind).param("a", amount).param("r", reason).param("at", timeOrNow(op.at())).param("u", cashier(d, op)).update();
        return Map.of("moveId", id.toString());
    }

    /** Cash that should be in the drawer: the opening float, plus cash taken for sales, minus cash given back for returns, plus cash in, minus cash out and drops to the safe. */
    long expectedCash(UUID shiftId) {
        return jdbc.sql("""
                SELECT s.opening_float_minor
                     + coalesce((SELECT sum(p.amount_minor) FROM commerce.order_payments p JOIN commerce.orders o ON o.id = p.order_id WHERE o.pos_shift_id = s.id AND p.kind = 'PAYMENT' AND p.method = 'CASH' AND p.status = 'SUCCEEDED'), 0)
                     - coalesce((SELECT sum(r.refund_minor) FROM commerce.order_returns r WHERE r.pos_shift_id = s.id AND r.refund_method = 'CASH'), 0)
                     + coalesce((SELECT sum(m.amount_minor) FROM commerce.pos_cash_moves m WHERE m.shift_id = s.id AND m.kind = 'PAY_IN'), 0)
                     - coalesce((SELECT sum(m.amount_minor) FROM commerce.pos_cash_moves m WHERE m.shift_id = s.id AND m.kind IN ('PAY_OUT', 'DROP')), 0)
                FROM commerce.pos_shifts s WHERE s.id = :s
                """).param("s", shiftId).query(Long.class).single();
    }

    private Map<String, Object> shiftClose(PosDeviceService.Device d, Op op, Map<String, Object> p, List<Map<String, Object>> flags) {
        UUID shift = shiftOf(d, p.get("shiftId"));
        long counted = amount(p.get("countedMinor"), true);
        Long deviceExpected = p.get("expectedMinor") instanceof Number n ? n.longValue() : null;
        long expected = expectedCash(shift);
        String kind = p.get("handoverKind") == null ? null : String.valueOf(p.get("handoverKind"));
        if (kind != null && !Set.of("NEXT_CASHIER", "SAFE").contains(kind)) kind = null;
        UUID handedTo = p.get("handedTo") == null ? null : uuid(p.get("handedTo"), "handedTo");
        jdbc.sql("""
                UPDATE commerce.pos_shifts SET status = 'CLOSED', closed_at = :at, counted_cash_minor = :c, expected_cash_minor = :e, difference_minor = :c - :e, device_expected_minor = :de,
                       denominations = CAST(:den AS jsonb), note = :n, handover_kind = :hk, handed_to = :ht
                WHERE id = :s AND status = 'OPEN'
                """).param("at", timeOrNow(op.at())).param("c", counted).param("e", expected).param("de", deviceExpected).param("den", p.get("denominations") == null ? null : json(p.get("denominations")))
                .param("n", text(p.get("note"), 500)).param("hk", kind).param("ht", handedTo).param("s", shift).update();
        if (deviceExpected != null && deviceExpected != expected)
            flags.add(Map.of("kind", "SHIFT_MISMATCH", "shiftId", shift.toString(), "terminalExpected", deviceExpected, "serverExpected", expected));
        audit.record(cashier(d, op), d.tenantId(), "POS_SHIFT_CLOSED", "pos_shift", shift, "{\"counted\":" + counted + ",\"expected\":" + expected + "}");
        return Map.of("shiftId", shift.toString(), "expectedMinor", expected, "differenceMinor", counted - expected);
    }

    // ---- sales and returns ----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> sale(PosDeviceService.Device d, Op op, Map<String, Object> p, List<Map<String, Object>> flags) {
        UUID orderId = uuid(p.get("orderId"), "orderId");
        if (jdbc.sql("SELECT count(*) FROM commerce.orders WHERE id = :o").param("o", orderId).query(Long.class).single() > 0) return Map.of("orderId", orderId.toString(), "duplicate", true);
        UUID shift = shiftOf(d, p.get("shiftId"));
        UUID cashier = cashier(d, op);
        if (!(p.get("items") instanceof List<?> rawItems) || rawItems.isEmpty() || rawItems.size() > 300) throw BusinessException.badRequest("VALIDATION_ERROR", "The sale has no items");
        String currency = String.valueOf(shop(d.tenantId()).get("currency"));
        long subtotal = 0, lineDiscounts = 0;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object o : rawItems) {
            Map<String, Object> it = (Map<String, Object>) o;
            UUID v = uuid(it.get("variantId"), "variantId");
            int qty = it.get("qty") instanceof Number n ? n.intValue() : 0;
            if (qty < 1 || qty > 10000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid quantity");
            long unit = amount(it.get("unitMinor"), true);
            long disc = it.get("discountMinor") instanceof Number n ? Math.max(0, n.longValue()) : 0;
            if (disc > unit * qty) disc = unit * qty;
            var row = jdbc.sql("SELECT v.id, v.product_id, v.sku, v.price_minor, v.combo_key, v.status AS vs, p.name, p.status AS ps FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.id = :v AND v.tenant_id = :t")
                    .param("v", v).param("t", d.tenantId()).query().listOfRows().stream().findFirst().orElse(null);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("variantId", row == null ? null : v);
            l.put("productId", row == null ? null : row.get("product_id"));
            l.put("sku", row != null ? row.get("sku") : text(it.get("sku"), 80) == null ? "-" : text(it.get("sku"), 80));
            l.put("name", row != null ? row.get("name") : text(it.get("name"), 250) == null ? "?" : text(it.get("name"), 250));
            String combo = row == null ? null : (String) row.get("combo_key");
            l.put("label", row == null ? text(it.get("label"), 250) : combo == null || combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": "));
            l.put("qty", qty); l.put("unit", unit); l.put("discount", disc); l.put("note", text(it.get("note"), 200));
            lines.add(l);
            subtotal += unit * qty;
            lineDiscounts += disc;
            if (row == null || !"ACTIVE".equals(row.get("vs")) || !"ACTIVE".equals(row.get("ps")))
                flags.add(Map.of("kind", "PRODUCT_INACTIVE", "variantId", v.toString(), "name", String.valueOf(l.get("name"))));
            else if (((Number) row.get("price_minor")).longValue() != unit)
                flags.add(Map.of("kind", "PRICE_CHANGED", "variantId", v.toString(), "name", String.valueOf(l.get("name")), "soldAtMinor", unit, "priceNowMinor", row.get("price_minor")));
        }
        long cartDiscount = p.get("discountMinor") instanceof Number n ? Math.max(0, n.longValue()) : 0;
        cartDiscount = Math.min(cartDiscount, subtotal - lineDiscounts);
        // the whole-sale discount is shared over the lines by their value (the last line takes the rounding), so every line says what was really paid for it
        // and a return of one piece gives back exactly that (the terminal shares it the same way)
        long net = subtotal - lineDiscounts, givenOut = 0;
        for (int i = 0; i < lines.size(); i++) {
            var l = lines.get(i);
            long lineNet = (long) l.get("unit") * (int) l.get("qty") - (long) l.get("discount");
            long share = i == lines.size() - 1 ? cartDiscount - givenOut : net == 0 ? 0 : cartDiscount * lineNet / net;
            share = Math.max(0, Math.min(share, lineNet));
            givenOut += share;
            l.put("discount", (long) l.get("discount") + share);
        }
        long discount = Math.min(subtotal, lineDiscounts + cartDiscount);
        long total = subtotal - discount;
        // payments: what the terminal took; a cash line is the part of the total paid in cash (the change is separate)
        List<Map<String, Object>> pays = new ArrayList<>();
        long paid = 0;
        if (p.get("payments") instanceof List<?> rawPays) for (Object o : rawPays) {
            Map<String, Object> x = (Map<String, Object>) o;
            String m = String.valueOf(x.get("method"));
            long a = x.get("amountMinor") instanceof Number n ? n.longValue() : 0;
            if (!PosService.METHODS.contains(m) || a <= 0) continue;
            pays.add(Map.of("method", m, "amount", a));
            paid += a;
        }
        if (pays.isEmpty()) { pays.add(Map.of("method", "CASH", "amount", total)); paid = total; }
        if (paid != total) flags.add(Map.of("kind", "SHIFT_MISMATCH", "orderId", orderId.toString(), "why", "payments " + paid + " differ from the total " + total));

        String name = text(p.get("customerName"), 200);
        if (name == null) name = "Walk-in customer";
        String phone = "";
        UUID customerId = null;
        if (p.get("phone") instanceof String ph && !ph.isBlank()) {
            try { phone = PhoneNormalizer.normalize(ph); customerId = customers.ensureCustomer(d.tenantId(), null, new StoreCustomerService.Contact(name, phone, null, "{}")); }
            catch (RuntimeException e) { phone = ""; }
        }
        String number = text(p.get("number"), 40);
        if (number == null) number = "POS-" + d.code() + "-" + String.format("%06d", op.seq());
        if (jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND order_number = :n").param("t", d.tenantId()).param("n", number).query(Long.class).single() > 0)
            number = number + "-" + orderId.toString().substring(0, 4).toUpperCase();   // only if the terminal's own copy was lost and a number came round again
        OffsetDateTime at = timeOrNow(op.at());
        String method = pays.size() == 1 ? (String) pays.get(0).get("method") : "SPLIT";
        jdbc.sql("""
                INSERT INTO commerce.orders (id, tenant_id, order_number, customer_id, status, payment_status, fulfillment_status, payment_method, currency, subtotal_minor, discount_minor, shipping_minor, total_minor,
                    customer_name_snapshot, customer_phone_snapshot, source, channel, confirmed_at, delivered_at, risk_level, risk_score,
                    pos_device_id, pos_shift_id, cashier_id, client_created_at, offline, tendered_minor, change_minor)
                VALUES (:id, :t, :n, :c, 'ARRIVED', 'PAID', 'FULFILLED', :pm, :cur, :sub, :disc, 0, :total, :cn, :cp, 'POS', 'POS', :at, :at, 'LOW', 0,
                    :dev, :sh, :cashier, :at, :off, :tend, :chg)
                """).param("id", orderId).param("t", d.tenantId()).param("n", number).param("c", customerId).param("pm", method).param("cur", currency).param("sub", subtotal).param("disc", discount)
                .param("total", total).param("cn", name).param("cp", phone).param("at", at).param("dev", d.id()).param("sh", shift).param("cashier", cashier)
                .param("off", Boolean.TRUE.equals(p.get("offline"))).param("tend", p.get("tenderedMinor") instanceof Number n ? n.longValue() : null).param("chg", p.get("changeMinor") instanceof Number n ? n.longValue() : null).update();
        for (var l : lines) {
            long lt = (long) l.get("unit") * (int) l.get("qty") - (long) l.get("discount");
            jdbc.sql("""
                    INSERT INTO commerce.order_items (order_id, tenant_id, product_id, variant_id, branch_id, sku_snapshot, product_name_snapshot, variant_name_snapshot, unit_price_minor, quantity, discount_minor, line_total_minor, note)
                    VALUES (:o, :t, :p, :v, :b, :sku, :pn, :vn, :up, :q, :dm, :lt, :note)
                    """).param("o", orderId).param("t", d.tenantId()).param("p", l.get("productId")).param("v", l.get("variantId")).param("b", d.branchId()).param("sku", l.get("sku")).param("pn", l.get("name"))
                    .param("vn", l.get("label")).param("up", l.get("unit")).param("q", l.get("qty")).param("dm", l.get("discount")).param("lt", lt).param("note", l.get("note")).update();
            if (l.get("variantId") != null) {
                int missing = inventory.sellCoveringWhatIsThere(d.tenantId(), d.branchId(), (UUID) l.get("variantId"), (int) l.get("qty"), orderId);
                if (missing > 0) flags.add(Map.of("kind", "NEGATIVE_STOCK", "variantId", String.valueOf(l.get("variantId")), "name", String.valueOf(l.get("name")), "sold", l.get("qty"), "notInStock", missing, "orderNumber", number));
            }
        }
        int i = 0;
        for (var pay : pays) {
            jdbc.sql("INSERT INTO commerce.order_payments (order_id, tenant_id, method, amount_minor, currency, status, paid_at, idempotency_key) VALUES (:o, :t, :m, :a, :c, 'SUCCEEDED', :at, :k)")
                    .param("o", orderId).param("t", d.tenantId()).param("m", pay.get("method")).param("a", pay.get("amount")).param("c", currency).param("at", at).param("k", "pos-" + orderId + "-" + i++).update();
        }
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, NULL, 'ARRIVED', :u, 'Point of sale')").param("o", orderId).param("t", d.tenantId()).param("u", cashier).update();
        if (!phone.isEmpty()) try { loyalty.earnForDelivered(d.tenantId(), orderId, phone, subtotal, discount); } catch (RuntimeException e) { log.warn("POS loyalty skipped: {}", e.toString()); }
        audit.record(cashier, d.tenantId(), "POS_SALE", "order", orderId, null);
        return Map.of("orderId", orderId.toString(), "orderNumber", number, "totalMinor", total);
    }

    /** A return at the till: by the sale's id (or its number), the pieces of each product that came back, the money given back (cash from the drawer, usually). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> giveBack(PosDeviceService.Device d, Op op, Map<String, Object> p, List<Map<String, Object>> flags) {
        UUID orderId = p.get("orderId") == null ? null : uuid(p.get("orderId"), "orderId");
        if (orderId == null && p.get("number") instanceof String n)
            orderId = jdbc.sql("SELECT id FROM commerce.orders WHERE tenant_id = :t AND order_number = :n").param("t", d.tenantId()).param("n", n.trim()).query(UUID.class).optional().orElse(null);
        if (orderId == null || jdbc.sql("SELECT count(*) FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", d.tenantId()).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "The sale being returned was not found");
        UUID shift = p.get("shiftId") == null ? null : shiftOf(d, p.get("shiftId"));
        String method = p.get("refundMethod") == null ? "CASH" : String.valueOf(p.get("refundMethod"));
        if (!PosService.METHODS.contains(method)) method = "CASH";
        // the pieces per product → the sale's lines (a product sold on two lines fills the first one first)
        List<ReturnService.Item> items = new ArrayList<>();
        var orderLines = jdbc.sql("SELECT i.id, i.variant_id, i.quantity - coalesce((SELECT sum(ri.quantity) FROM commerce.order_return_items ri WHERE ri.order_item_id = i.id), 0) AS left_qty FROM commerce.order_items i WHERE i.order_id = :o ORDER BY i.created_at, i.id")
                .param("o", orderId).query().listOfRows();
        List<Map<String, Object>> exceeded = new ArrayList<>();
        if (p.get("items") instanceof List<?> raw) for (Object o : raw) {
            Map<String, Object> it = (Map<String, Object>) o;
            int qty = it.get("qty") instanceof Number n ? n.intValue() : 0;
            if (qty <= 0) continue;
            UUID v = it.get("variantId") == null ? null : uuid(it.get("variantId"), "variantId");
            UUID lineId = it.get("orderItemId") == null ? null : uuid(it.get("orderItemId"), "orderItemId");
            int want = qty;
            for (var ol : orderLines) {
                if (want == 0) break;
                if (lineId != null ? !lineId.equals(ol.get("id")) : v == null || !v.equals(ol.get("variant_id"))) continue;
                int left = ((Number) ol.get("left_qty")).intValue();
                int take = Math.min(want, Math.max(0, left));
                if (take > 0) { items.add(new ReturnService.Item((UUID) ol.get("id"), take)); ol.put("left_qty", left - take); want -= take; }
            }
            if (want > 0) exceeded.add(Map.of("variantId", String.valueOf(v), "asked", qty, "notReturnable", want));
        }
        Map<String, Object> r = items.isEmpty() ? Map.of("refundMinor", 0, "nothingApplied", true)
                : returns.create(d.tenantId(), cashier(d, op), new ReturnService.ReturnReq(orderId, items, text(p.get("reason"), 300)), op.opId(), shift, method, exceeded);
        if (!exceeded.isEmpty()) flags.add(Map.of("kind", "RETURN_EXCEEDS", "orderId", orderId.toString(), "lines", exceeded));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId.toString());
        out.put("refundMinor", r.get("refundMinor"));
        return out;
    }

    // ---- stock counts ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> count(PosDeviceService.Device d, Op op, Map<String, Object> p) {
        UUID id = uuid(p.get("countId"), "countId");
        String name = text(p.get("name"), 120);
        UUID by = cashier(d, op);
        jdbc.sql("INSERT INTO commerce.stock_counts (id, tenant_id, branch_id, name, created_by, created_at) VALUES (:i, :t, :b, :n, :u, :at) ON CONFLICT (id) DO NOTHING")
                .param("i", id).param("t", d.tenantId()).param("b", d.branchId()).param("n", name == null ? "Stock count" : name).param("u", by).param("at", timeOrNow(op.at())).update();
        String status = jdbc.sql("SELECT status FROM commerce.stock_counts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", d.tenantId()).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Stock count not found"));
        if (!Set.of("COUNTING", "REVIEW").contains(status)) throw BusinessException.conflict("COUNT_CLOSED", "This stock count was already applied or cancelled");
        int n = 0;
        if (p.get("lines") instanceof List<?> raw) for (Object o : raw) {
            Map<String, Object> l = (Map<String, Object>) o;
            UUID v = uuid(l.get("variantId"), "variantId");
            int counted = l.get("counted") instanceof Number c ? c.intValue() : -1;
            int expected = l.get("expected") instanceof Number e ? e.intValue() : 0;
            if (counted < 0 || counted > 1_000_000) continue;
            jdbc.sql("""
                    INSERT INTO commerce.stock_count_lines (count_id, variant_id, expected_at_count, counted, counted_at, counted_by) VALUES (:c, :v, :e, :n, :at, :u)
                    ON CONFLICT (count_id, variant_id) DO UPDATE SET counted = EXCLUDED.counted, expected_at_count = EXCLUDED.expected_at_count, counted_at = EXCLUDED.counted_at, counted_by = EXCLUDED.counted_by
                    """).param("c", id).param("v", v).param("e", expected).param("n", counted).param("at", time(String.valueOf(l.get("at"))) == null ? timeOrNow(op.at()) : time(String.valueOf(l.get("at")))).param("u", by).update();
            n++;
        }
        if ("REVIEW".equals(p.get("status"))) jdbc.sql("UPDATE commerce.stock_counts SET status = 'REVIEW' WHERE id = :i AND status = 'COUNTING'").param("i", id).update();
        return Map.of("countId", id.toString(), "lines", n);
    }

    // ---- small helpers --------------------------------------------------------------------------------------------------

    private UUID cashier(PosDeviceService.Device d, Op op) {
        if (op.cashierId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "No cashier on this action");
        if (jdbc.sql("SELECT count(*) FROM core.user_tenant_memberships WHERE tenant_id = :t AND user_id = :u").param("t", d.tenantId()).param("u", op.cashierId()).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "This cashier is not on the team");
        return op.cashierId();
    }

    private UUID shiftOf(PosDeviceService.Device d, Object raw) {
        UUID id = uuid(raw, "shiftId");
        if (jdbc.sql("SELECT count(*) FROM commerce.pos_shifts WHERE id = :s AND tenant_id = :t").param("s", id).param("t", d.tenantId()).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "Shift not found");
        return id;
    }

    private static UUID uuid(Object raw, String what) {
        try { return UUID.fromString(String.valueOf(raw)); } catch (IllegalArgumentException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid " + what); }
    }

    private static long amount(Object raw, boolean zeroOk) {
        long a = raw instanceof Number n ? n.longValue() : -1;
        if (a < 0 || (!zeroOk && a == 0) || a > 100_000_000_000L) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid amount");
        return a;
    }

    private static String text(Object raw, int max) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static OffsetDateTime time(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return OffsetDateTime.parse(raw); } catch (DateTimeParseException e) { return null; }
    }

    private static OffsetDateTime timeOrNow(String raw) {
        OffsetDateTime t = time(raw);
        return t == null || t.isAfter(OffsetDateTime.now().plusMinutes(10)) ? OffsetDateTime.now() : t;
    }

    private static String json(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}

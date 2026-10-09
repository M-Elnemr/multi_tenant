package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Delivery zones by governorate, order confirmation and tracking, public order tracking for guests, return requests. */
@Service
public class StoreFulfillmentService {
    private static final Set<String> GOVERNORATES = Set.of("CAI", "GIZ", "ALX", "QLY", "SHR", "DKH", "GHR", "MNF", "BHR", "KFS", "DMT", "PSD", "ISM", "SUZ", "FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN", "RSS", "WAD", "MTR", "NSN", "SSN");
    private static final Set<String> REASONS = Set.of("DEFECTIVE", "WRONG_ITEM", "NOT_AS_DESCRIBED", "SIZE", "CHANGED_MIND", "OTHER");

    private final JdbcClient jdbc;
    private final OrderService orders;
    private final AuditService audit;
    private final PhoneFlags flags;

    public StoreFulfillmentService(JdbcClient jdbc, OrderService orders, AuditService audit, PhoneFlags flags) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.audit = audit;
        this.flags = flags;
    }

    // ---- delivery zones --------------------------------------------------------------------------------------

    public List<Map<String, Object>> zones(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("SELECT id, name, array_to_string(governorate_codes, ',') AS codes, fee_minor, free_above_minor, cod_fee_minor, eta_min_days, eta_max_days, is_active, sort_order "
                        + "FROM commerce.shipping_zones WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY sort_order, name").param("t", tenantId).param("a", onlyActive).query().listOfRows())
                .stream().map(z -> { String c = (String) z.remove("codes"); z.put("governorateCodes", c == null || c.isEmpty() ? List.of() : List.of(c.split(","))); return z; }).toList();
    }

    @Transactional
    public List<Map<String, Object>> saveZone(UUID tenantId, UUID actor, UUID id, Map<String, Object> f) {
        String name = f.get("name") instanceof String s ? s.trim() : null;
        List<String> codes = null;
        if (f.get("governorateCodes") instanceof List<?> l) {
            codes = l.stream().map(String::valueOf).map(String::toUpperCase).distinct().toList();
            if (!GOVERNORATES.containsAll(codes)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown governorate");
            // a governorate belongs to one zone: take it from the others so pricing is never ambiguous
            for (String c : codes) jdbc.sql("UPDATE commerce.shipping_zones SET governorate_codes = array_remove(governorate_codes, :c) WHERE tenant_id = :t AND (CAST(:i AS uuid) IS NULL OR id <> CAST(:i AS uuid))")
                    .param("c", c).param("t", tenantId).param("i", id).update();
        }
        Long fee = num(f.get("feeMinor")), freeAbove = num(f.get("freeAboveMinor")), cod = num(f.get("codFeeMinor"));
        Integer etaMin = f.get("etaMinDays") instanceof Number n ? n.intValue() : null, etaMax = f.get("etaMaxDays") instanceof Number n ? n.intValue() : null;
        if ((fee != null && fee < 0) || (cod != null && cod < 0) || (etaMin != null && etaMin < 0) || (etaMax != null && etaMax < (etaMin == null ? 0 : etaMin))) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid zone values");
        String codesCsv = codes == null ? null : String.join(",", codes);
        if (id == null) {
            if (name == null || name.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name is required");
            jdbc.sql("INSERT INTO commerce.shipping_zones (tenant_id, name, governorate_codes, fee_minor, free_above_minor, cod_fee_minor, eta_min_days, eta_max_days) VALUES (:t, :n, string_to_array(:c, ',')::text[], :f, :fa, :cf, :emin, :emax)")
                    .param("t", tenantId).param("n", name).param("c", codesCsv == null ? "" : codesCsv).param("f", fee == null ? 0 : fee).param("fa", freeAbove).param("cf", cod == null ? 0 : cod)
                    .param("emin", etaMin == null ? 1 : etaMin).param("emax", etaMax == null ? 3 : etaMax).update();
        } else {
            int n = jdbc.sql("UPDATE commerce.shipping_zones SET name = coalesce(:n, name), governorate_codes = CASE WHEN CAST(:c AS varchar) IS NULL THEN governorate_codes ELSE string_to_array(NULLIF(CAST(:c AS varchar), ''), ',')::text[] END, "
                            + "fee_minor = coalesce(:f, fee_minor), free_above_minor = CASE WHEN :clearFree THEN NULL ELSE coalesce(:fa, free_above_minor) END, cod_fee_minor = coalesce(:cf, cod_fee_minor), "
                            + "eta_min_days = coalesce(:emin, eta_min_days), eta_max_days = coalesce(:emax, eta_max_days), is_active = coalesce(:ac, is_active) WHERE id = :i AND tenant_id = :t")
                    .param("n", name == null || name.isEmpty() ? null : name).param("c", codesCsv).param("f", fee).param("fa", freeAbove).param("clearFree", f.containsKey("freeAboveMinor") && f.get("freeAboveMinor") == null)
                    .param("cf", cod).param("emin", etaMin).param("emax", etaMax).param("ac", f.get("isActive") instanceof Boolean b ? b : null).param("i", id).param("t", tenantId).update();
            if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        }
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "shipping_zone", id, null);
        return zones(tenantId, false);
    }

    @Transactional
    public List<Map<String, Object>> deleteZone(UUID tenantId, UUID actor, UUID id) {
        jdbc.sql("DELETE FROM commerce.shipping_zones WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "shipping_zone", id, null);
        return zones(tenantId, false);
    }

    /** The active zone that serves this governorate, if any. */
    public Map<String, Object> zoneFor(UUID tenantId, String governorateCode) {
        if (governorateCode == null || governorateCode.isBlank()) return null;
        return jdbc.sql("SELECT id, name, fee_minor, free_above_minor, cod_fee_minor, eta_min_days, eta_max_days FROM commerce.shipping_zones WHERE tenant_id = :t AND is_active AND :g = ANY(governorate_codes) LIMIT 1")
                .param("t", tenantId).param("g", governorateCode.trim().toUpperCase()).query().listOfRows().stream().findFirst().orElse(null);
    }

    // ---- confirmation and tracking (owner) --------------------------------------------------------------------

    /** The owner called or messaged the customer and the order is real. */
    @Transactional
    public Map<String, Object> confirm(UUID tenantId, UUID actor, UUID orderId) {
        if (jdbc.sql("UPDATE commerce.orders SET confirmed_at = coalesce(confirmed_at, now()), confirmed_by = coalesce(confirmed_by, :a), updated_at = now() WHERE id = :o AND tenant_id = :t AND status IN ('REQUESTED','PREPARING')")
                .param("a", actor).param("o", orderId).param("t", tenantId).update() == 0) throw BusinessException.badRequest("INVALID_STATUS_TRANSITION", "This order cannot be confirmed now");
        audit.record(actor, tenantId, "ORDER_CONFIRMED", "order", orderId, null);
        return orders.get(tenantId, orderId);
    }

    @Transactional
    public Map<String, Object> setTracking(UUID tenantId, UUID actor, UUID orderId, String courier, String number, String url) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.matches("(?i)^https?://\\S+$")) throw BusinessException.badRequest("VALIDATION_ERROR", "Tracking link must start with http(s)://");
        if (jdbc.sql("UPDATE commerce.orders SET courier_name = :c, tracking_number = :n, tracking_url = :u, updated_at = now() WHERE id = :o AND tenant_id = :t")
                .param("c", courier == null ? "" : courier.trim()).param("n", number == null ? "" : number.trim()).param("u", u).param("o", orderId).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
        audit.record(actor, tenantId, "ORDER_TRACKING_SET", "order", orderId, null);
        return orders.get(tenantId, orderId);
    }

    // ---- public tracking (guests included) ---------------------------------------------------------------------

    private Map<String, Object> lookup(UUID tenantId, String orderNumber, String phone) {
        String p = phone == null ? "" : PhoneNormalizer.normalize(phone);
        return jdbc.sql("SELECT * FROM commerce.orders WHERE tenant_id = :t AND upper(order_number) = upper(:n) AND customer_phone_snapshot = :p")
                .param("t", tenantId).param("n", orderNumber == null ? "" : orderNumber.trim()).param("p", p).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "We could not find an order with this number and phone"));
    }

    private int returnWindowDays(UUID tenantId) {
        return jdbc.sql("SELECT return_window_days FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query(Integer.class).optional().orElse(14);
    }

    public Map<String, Object> track(UUID tenantId, String orderNumber, String phone) {
        var o = lookup(tenantId, orderNumber, phone);
        UUID id = (UUID) o.get("id");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String k : List.of("order_number", "status", "payment_method", "currency", "subtotal_minor", "discount_minor", "shipping_minor", "cod_fee_minor", "total_minor", "courier_name", "tracking_number", "tracking_url", "governorate_code", "eta_min_days", "eta_max_days", "created_at", "shipped_at", "delivered_at"))
            out.put(Rows.camel(Map.of(k, "")).keySet().iterator().next(), o.get(k));
        out.put("confirmed", o.get("confirmed_at") != null);
        out.put("items", Rows.camel(jdbc.sql("SELECT product_name_snapshot, variant_name_snapshot, unit_price_minor, quantity, line_total_minor FROM commerce.order_items WHERE order_id = :o ORDER BY created_at, id").param("o", id).query().listOfRows()));
        out.put("history", Rows.camel(jdbc.sql("SELECT new_status, created_at FROM commerce.order_status_history WHERE order_id = :o ORDER BY created_at, id").param("o", id).query().listOfRows()));
        var ret = jdbc.sql("SELECT status, reason, created_at FROM commerce.return_requests WHERE order_id = :o ORDER BY created_at DESC LIMIT 1").param("o", id).query().listOfRows().stream().findFirst();
        out.put("returnRequest", ret.map(Rows::camel).orElse(null));
        out.put("canReturn", returnAllowed(tenantId, o) && (ret.isEmpty() || "REJECTED".equals(ret.get().get("status"))));
        return out;
    }

    private boolean returnAllowed(UUID tenantId, Map<String, Object> o) {
        if (!"ARRIVED".equals(o.get("status"))) return false;
        var delivered = (java.sql.Timestamp) o.get("delivered_at");
        Instant from = delivered != null ? delivered.toInstant() : ((java.sql.Timestamp) o.get("updated_at")).toInstant();
        return Instant.now().isBefore(from.plus(returnWindowDays(tenantId), ChronoUnit.DAYS));
    }

    @Transactional
    public Map<String, Object> requestReturn(UUID tenantId, String orderNumber, String phone, String reason, String details) {
        var o = lookup(tenantId, orderNumber, phone);
        if (!returnAllowed(tenantId, o)) throw BusinessException.badRequest("RETURN_NOT_ALLOWED", "This order can no longer be returned online. Please contact the shop.");
        String r = reason == null ? "OTHER" : reason.trim().toUpperCase();
        if (!REASONS.contains(r)) r = "OTHER";
        try {
            jdbc.sql("INSERT INTO commerce.return_requests (tenant_id, order_id, reason, details) VALUES (:t, :o, :r, :d)")
                    .param("t", tenantId).param("o", o.get("id")).param("r", r).param("d", details == null ? "" : details.trim().substring(0, Math.min(1000, details.trim().length()))).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.conflict("RETURN_ALREADY_REQUESTED", "A return request for this order is already open");
        }
        audit.record(null, tenantId, "RETURN_REQUESTED", "order", (UUID) o.get("id"), null);
        return track(tenantId, orderNumber, phone);
    }

    // ---- returns (owner) -----------------------------------------------------------------------------------------

    public Map<String, Object> returns(UUID tenantId, Page page, String status) {
        String where = "r.tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR r.status = CAST(:s AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM commerce.return_requests r WHERE " + where).param("t", tenantId).param("s", status).query(Long.class).single();
        var rows = jdbc.sql("SELECT r.id, r.order_id, r.reason, r.details, r.status, r.owner_note, r.created_at, o.order_number, o.customer_name_snapshot, o.customer_phone_snapshot, o.total_minor, o.currency "
                        + "FROM commerce.return_requests r JOIN commerce.orders o ON o.id = r.order_id WHERE " + where + " ORDER BY r.created_at DESC LIMIT :lim OFFSET :off")
                .param("t", tenantId).param("s", status).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    /** APPROVED: customer may send it back. REJECTED: closed. RECEIVED: goods are back, the order moves to RETURNED and stock is restored. */
    @Transactional
    public Map<String, Object> decideReturn(UUID tenantId, UUID actor, UUID id, String status, String note) {
        if (!Set.of("APPROVED", "REJECTED", "RECEIVED").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");
        var r = jdbc.sql("SELECT order_id, status FROM commerce.return_requests WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found"));
        String cur = (String) r.get("status");
        boolean ok = ("REQUESTED".equals(cur) && Set.of("APPROVED", "REJECTED").contains(status)) || ("APPROVED".equals(cur) && Set.of("RECEIVED", "REJECTED").contains(status));
        if (!ok) throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Cannot move a return from " + cur + " to " + status);
        jdbc.sql("UPDATE commerce.return_requests SET status = :s, owner_note = :n, decided_by = :a, decided_at = now() WHERE id = :i").param("s", status).param("n", note == null ? "" : note.trim()).param("a", actor).param("i", id).update();
        if ("RECEIVED".equals(status)) orders.changeStatus(tenantId, (UUID) r.get("order_id"), "RETURNED", actor, "Return received", null);
        audit.record(actor, tenantId, "RETURN_DECIDED", "return_request", id, "{\"status\":\"" + status + "\"}");
        return Map.of("ok", true, "status", status);
    }

    // ---- phone history (owner) ------------------------------------------------------------------------------------

    public List<Map<String, Object>> flaggedPhones(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT phone, delivered_count, returned_count, cancelled_count, blocked, note FROM commerce.phone_flags WHERE tenant_id = :t AND (blocked OR returned_count > 0 OR cancelled_count > 1) ORDER BY blocked DESC, returned_count DESC, updated_at DESC LIMIT 200")
                .param("t", tenantId).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> blockPhone(UUID tenantId, UUID actor, String phone, boolean blocked, String note) {
        String p = PhoneNormalizer.normalize(phone == null ? "" : phone);
        if (p.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Phone is required");
        flags.setBlocked(tenantId, p, blocked, note);
        audit.record(actor, tenantId, "PHONE_BLOCK_CHANGED", "phone", null, "{\"blocked\":" + blocked + "}");
        return flags.stats(tenantId, p);
    }

    private static Long num(Object o) { return o instanceof Number n ? n.longValue() : null; }
}

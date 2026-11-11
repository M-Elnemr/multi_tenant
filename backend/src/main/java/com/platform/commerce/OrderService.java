package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order lifecycle: validated transitions (spec 54), stock side effects, history, refunds. */
@Service
public class OrderService {
    /** REQUESTED -> PREPARING -> SHIPPED -> ARRIVED, with CANCELLED and RETURNED as the other ends. (PENDING is only an unpaid card order, which is switched off.) */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("REQUESTED", "CANCELLED"),
            "REQUESTED", Set.of("PREPARING", "CANCELLED"),
            "PREPARING", Set.of("SHIPPED", "CANCELLED"),
            "SHIPPED", Set.of("ARRIVED", "RETURNED"),
            "ARRIVED", Set.of("RETURNED"),
            "RETURNED", Set.of("REFUNDED"));

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final LoyaltyService loyalty;
    private final PhoneFlags phoneFlags;

    public OrderService(JdbcClient jdbc, InventoryService inventory, AuditService audit, ApplicationEventPublisher events, PhoneFlags phoneFlags, LoyaltyService loyalty) {
        this.phoneFlags = phoneFlags;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
        this.events = events;
        this.loyalty = loyalty;
    }

    /** Staff-driven (actor = staff user) or customer-driven (customerUserId set) status change. */
    @Transactional
    public Map<String, Object> changeStatus(UUID tenantId, UUID orderId, String newStatus, UUID actor, String reason, UUID customerUserId) {
        var o = lock(tenantId, orderId);
        if (customerUserId != null) {
            if (!customerUserId.equals(o.get("user_id"))) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
            if (!Set.of("PENDING", "REQUESTED").contains((String) o.get("status")) || !"CANCELLED".equals(newStatus))
                throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "This order can no longer be cancelled by the customer");
        }
        if ("REFUNDED".equals(newStatus)) throw BusinessException.badRequest("INVALID_STATUS_TRANSITION", "Use the refund endpoint");
        apply(tenantId, o, newStatus, actor != null ? actor : customerUserId, reason);
        audit.record(actor, tenantId, "ORDER_STATUS_CHANGED", "order", orderId, "{\"to\":\"" + newStatus + "\"}");
        if (customerUserId != null && "CANCELLED".equals(newStatus))
            events.publishEvent(new OrderEvents.OrderCancelledByCustomer(tenantId, orderId, (String) o.get("order_number")));
        return get(tenantId, orderId);
    }

    /** The customer cancelled in the chat without giving a reason: the order is cancelled (stock back) and the shop is told. */
    @Transactional
    public void cancelByChat(UUID tenantId, UUID orderId) {
        var o = lock(tenantId, orderId);
        if (!changeable((String) o.get("status")))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "The shop has already started this order");
        apply(tenantId, o, "CANCELLED", null, "Cancelled by the customer in the chat (no reason given)");
        audit.record(null, tenantId, "ORDER_STATUS_CHANGED", "order", orderId, "{\"to\":\"CANCELLED\",\"by\":\"chat\"}");
        events.publishEvent(new OrderEvents.OrderCancelledByCustomer(tenantId, orderId, (String) o.get("order_number")));
    }

    /** The customer wants to cancel and said why: the order stays, the reason is written on it and the shop is told, so they can call the customer and solve it. */
    @Transactional
    public void requestCancelByChat(UUID tenantId, UUID orderId, String reason) {
        var o = lock(tenantId, orderId);
        String r = reason.length() > 500 ? reason.substring(0, 500) : reason;
        String line = "⚠️ طلب إلغاء من العميل — السبب: " + r;
        if (String.valueOf(o.get("notes")).contains(line)) return;   // the same request again (a second model pass, or the customer said it twice): the shop already has it
        jdbc.sql("UPDATE commerce.orders SET notes = concat_ws(E'\\n', NULLIF(notes, ''), :n), updated_at = now() WHERE id = :o")
                .param("n", line).param("o", orderId).update();
        audit.record(null, tenantId, "ORDER_CANCEL_REQUESTED", "order", orderId, null);
        events.publishEvent(new OrderEvents.OrderCancelRequested(tenantId, orderId, (String) o.get("order_number"), r));
    }

    /** Whether the customer may still change this order in the chat: nothing has been prepared yet. */
    public static boolean changeable(String status) { return "PENDING".equals(status) || "REQUESTED".equals(status); }

    /**
     * The customer changed a placed order in the chat (added, removed or swapped items): the new order is already placed, so the old one is closed as replaced. Its stock goes back, it is not counted as
     * a cancellation against the customer's phone and the customer is not told "cancelled" (they asked for the change and got the new order number).
     */
    @Transactional
    public void replaceByChat(UUID tenantId, UUID orderId, String newOrderNumber) {
        var o = lock(tenantId, orderId);
        if (!changeable((String) o.get("status")))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "The shop has already started this order");
        for (var it : jdbc.sql("SELECT variant_id, branch_id, quantity FROM commerce.order_items WHERE order_id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).query().listOfRows())
            inventory.release(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
        jdbc.sql("UPDATE commerce.order_payments SET status = 'FAILED', updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'PENDING'").param("o", orderId).update();
        jdbc.sql("UPDATE commerce.orders SET status = 'CANCELLED', reservation_expires_at = NULL, updated_at = now() WHERE id = :o").param("o", orderId).update();
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, :f, 'CANCELLED', NULL, :r)")
                .param("o", orderId).param("t", tenantId).param("f", o.get("status")).param("r", "Changed by the customer in the chat: replaced by order " + newOrderNumber).update();
        audit.record(null, tenantId, "ORDER_REPLACED_IN_CHAT", "order", orderId, "{\"by\":\"" + newOrderNumber + "\"}");
    }

    /** Core transition. Caller already holds the row lock. */
    void apply(UUID tenantId, Map<String, Object> o, String newStatus, UUID changedBy, String reason) {
        UUID orderId = (UUID) o.get("id");
        String from = (String) o.get("status");
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(newStatus))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Cannot move an order from " + from + " to " + newStatus);

        if (Set.of("PREPARING", "SHIPPED").contains(newStatus) && Boolean.TRUE.equals(o.get("needs_confirmation")) && o.get("confirmed_at") == null)
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "ORDER_NEEDS_CONFIRMATION", "This order looks risky: call the customer and confirm it before preparing or shipping it");
        var items = jdbc.sql("SELECT variant_id, branch_id, quantity FROM commerce.order_items WHERE order_id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).query().listOfRows();
        switch (newStatus) {
            case "CANCELLED" -> {
                for (var it : items) inventory.release(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                jdbc.sql("UPDATE commerce.order_payments SET status = 'FAILED', updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'PENDING'").param("o", orderId).update();
            }
            case "ARRIVED" -> {
                for (var it : items) inventory.sell(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                if ("CASH_ON_DELIVERY".equals(o.get("payment_method"))) {
                    jdbc.sql("UPDATE commerce.order_payments SET status = 'SUCCEEDED', paid_at = now(), updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT'").param("o", orderId).update();
                    jdbc.sql("UPDATE commerce.orders SET payment_status = 'PAID' WHERE id = :o").param("o", orderId).update();
                }
            }
            case "RETURNED" -> {
                // from SHIPPED the goods were never counted as sold (that happens on arrival), so the reservation is simply released
                for (var it : items) {
                    if ("SHIPPED".equals(from)) inventory.release(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                    else inventory.restock(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                }
            }
            default -> { }
        }
        String phone = (String) o.get("customer_phone_snapshot");
        switch (newStatus) {
            case "SHIPPED" -> jdbc.sql("UPDATE commerce.orders SET shipped_at = coalesce(shipped_at, now()), confirmed_at = coalesce(confirmed_at, now()) WHERE id = :o").param("o", orderId).update();
            case "ARRIVED" -> { jdbc.sql("UPDATE commerce.orders SET delivered_at = now() WHERE id = :o").param("o", orderId).update(); phoneFlags.bump(tenantId, phone, "delivered_count");
                loyalty.earnForDelivered(tenantId, orderId, phone, ((Number) o.get("subtotal_minor")).longValue(), ((Number) o.get("discount_minor")).longValue()); }
            case "RETURNED" -> { phoneFlags.bump(tenantId, phone, "returned_count"); if ("ARRIVED".equals(from)) loyalty.reverseForReturned(tenantId, orderId, phone); }
            case "CANCELLED" -> phoneFlags.bump(tenantId, phone, "cancelled_count");
            default -> { }
        }
        jdbc.sql("UPDATE commerce.orders SET status = :s, fulfillment_status = :f, reservation_expires_at = NULL, updated_at = now() WHERE id = :o")
                .param("s", newStatus).param("f", fulfillment(newStatus, (String) o.get("fulfillment_status"))).param("o", orderId).update();
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, :f, :n, :c, :r)")
                .param("o", orderId).param("t", tenantId).param("f", from).param("n", newStatus).param("c", changedBy).param("r", reason).update();
        events.publishEvent(new OrderEvents.OrderStatusChanged(tenantId, orderId, (UUID) o.get("user_id"), (String) o.get("order_number"), newStatus));
    }

    private static String fulfillment(String status, String current) {
        return switch (status) {
            case "PREPARING" -> "PROCESSING";
            case "SHIPPED" -> "SHIPPED";
            case "ARRIVED" -> "FULFILLED";
            default -> current;
        };
    }

    /** Verified provider callback: marks the card payment paid. Idempotent; amount must equal the server total. */
    @Transactional
    public boolean markCardPaid(UUID tenantId, UUID orderId, long amountMinor, String providerPaymentId) {
        var o = lock(tenantId, orderId);
        if ("PAID".equals(o.get("payment_status"))) return false;   // repeated delivery
        long total = ((Number) o.get("total_minor")).longValue();
        if (amountMinor != total) {
            jdbc.sql("UPDATE commerce.order_payments SET status = 'FAILED', updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'PENDING'").param("o", orderId).update();
            audit.record(null, tenantId, "PAYMENT_AMOUNT_MISMATCH", "order", orderId, "{\"expected\":" + total + ",\"got\":" + amountMinor + "}");
            throw BusinessException.badRequest("PAYMENT_AMOUNT_MISMATCH", "Paid amount does not match the order total");
        }
        jdbc.sql("UPDATE commerce.order_payments SET status = 'SUCCEEDED', paid_at = now(), provider_payment_id = coalesce(:p, provider_payment_id), updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT'")
                .param("p", providerPaymentId).param("o", orderId).update();
        jdbc.sql("UPDATE commerce.orders SET payment_status = 'PAID', updated_at = now() WHERE id = :o").param("o", orderId).update();
        if ("PENDING".equals(o.get("status"))) apply(tenantId, o, "REQUESTED", null, "Card payment received");
        else audit.record(null, tenantId, "PAYMENT_AFTER_CLOSE", "order", orderId, "{\"status\":\"" + o.get("status") + "\"}");
        return true;
    }

    /** Unpaid card orders release their stock after the reservation window. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @Transactional
    public void releaseExpiredReservations() {
        var expired = jdbc.sql("SELECT id, tenant_id FROM commerce.orders WHERE status = 'PENDING' AND payment_method = 'CARD' AND payment_status = 'UNPAID' AND reservation_expires_at < now() FOR UPDATE SKIP LOCKED")
                .query().listOfRows();
        for (var e : expired) {
            UUID tenantId = (UUID) e.get("tenant_id");
            apply(tenantId, lock(tenantId, (UUID) e.get("id")), "CANCELLED", null, "Payment not received in time");
        }
    }

    @Transactional
    public Map<String, Object> refund(UUID tenantId, UUID actor, UUID orderId, long amountMinor, String reason) { return refund(tenantId, actor, orderId, amountMinor, reason, null); }

    /** {@code method}: how the money went back when it is not the way it came in (a till return paid in cash from the drawer); null = as it was paid. */
    public Map<String, Object> refund(UUID tenantId, UUID actor, UUID orderId, long amountMinor, String reason, String method) {
        var o = lock(tenantId, orderId);
        if (!Set.of("PAID", "PARTIALLY_REFUNDED").contains((String) o.get("payment_status")))
            throw BusinessException.badRequest("REFUND_NOT_ALLOWED", "This order has no paid amount to refund");
        var pays = jdbc.sql("SELECT id, amount_minor, provider, method, currency FROM commerce.order_payments WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'SUCCEEDED' ORDER BY created_at, id")
                .param("o", orderId).query().listOfRows();
        if (pays.isEmpty()) throw BusinessException.badRequest("REFUND_NOT_ALLOWED", "No successful payment");
        var pay = pays.stream().filter(p -> method != null && method.equals(p.get("method"))).findFirst().orElse(pays.get(0));   // a till sale paid part cash, part card: the refund links to the payment of its own kind
        long paid = pays.stream().mapToLong(p -> ((Number) p.get("amount_minor")).longValue()).sum();
        long refunded = jdbc.sql("SELECT coalesce(sum(amount_minor),0) FROM commerce.order_payments WHERE order_id = :o AND kind = 'REFUND' AND status = 'SUCCEEDED'").param("o", orderId).query(Long.class).single();
        if (amountMinor <= 0 || refunded + amountMinor > paid)
            throw BusinessException.badRequest("REFUND_EXCEEDS_PAID", "Refund exceeds the paid amount");
        // A refund is always linked to the original payment (spec 73).
        jdbc.sql("""
                INSERT INTO commerce.order_payments (order_id, tenant_id, kind, original_payment_id, provider, method, amount_minor, currency, status, idempotency_key)
                VALUES (:o, :t, 'REFUND', :p, :pv, :m, :a, :c, 'SUCCEEDED', :k)
                """).param("o", orderId).param("t", tenantId).param("p", pay.get("id")).param("pv", pay.get("provider")).param("m", method != null ? method : pay.get("method"))
                .param("a", amountMinor).param("c", pay.get("currency")).param("k", "refund-" + UUID.randomUUID()).update();
        boolean full = refunded + amountMinor == paid;
        jdbc.sql("UPDATE commerce.orders SET payment_status = :s, updated_at = now() WHERE id = :o").param("s", full ? "REFUNDED" : "PARTIALLY_REFUNDED").param("o", orderId).update();
        if (full && "RETURNED".equals(o.get("status"))) apply(tenantId, o, "REFUNDED", actor, reason);
        audit.record(actor, tenantId, "REFUND_CREATED", "order", orderId, "{\"amountMinor\":" + amountMinor + "}");
        return get(tenantId, orderId);
    }

    // ---- reads -------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, Page page, String status, UUID userId) { return list(tenantId, page, status, userId, null, null, null, null); }

    /** unconfirmed=true: new COD orders nobody has called yet. q matches order number, name or phone. */
    public Map<String, Object> list(UUID tenantId, Page page, String status, UUID userId, Boolean unconfirmed, String governorate, String q, String channel) {
        return list(tenantId, page, new Filter(status, userId, unconfirmed, governorate, q, channel, null, null, null, null, null, null, null, null, null, null, null));
    }

    /**
     * Everything the orders screen can filter by. statuses: several at once (comma separated); paymentMethod/paymentStatus; total range in minor units;
     * discount: "COUPON" (a coupon was used), "OFFER" (an automatic offer applied), "ANY", "NONE"; risk: LOW/MEDIUM/HIGH; branchId: an item shipped from that branch;
     * sort: NEWEST (default), OLDEST, TOTAL_DESC, TOTAL_ASC.
     */
    public record Filter(String status, UUID userId, Boolean unconfirmed, String governorate, String q, String channel,
                         java.time.LocalDate from, java.time.LocalDate to, String paymentMethod, String paymentStatus, Long minTotal, Long maxTotal,
                         String discount, String risk, UUID branchId, String source, String sort) {}

    public Map<String, Object> list(UUID tenantId, Page page, Filter f) {
        com.platform.shared.DateRange.check(f.from(), f.to());
        String like = f.q() == null || f.q().isBlank() ? null : "%" + f.q().trim() + "%";
        List<String> statuses = f.status() == null || f.status().isBlank() ? List.of() : java.util.Arrays.stream(f.status().split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        String discount = f.discount() == null ? "" : f.discount().toUpperCase();
        String where = "o.tenant_id = :t AND (:sn = 0 OR o.status = ANY(string_to_array(:ss, ','))) AND (CAST(:u AS uuid) IS NULL OR o.user_id = CAST(:u AS uuid))"
                + " AND (NOT :unc OR (o.status = 'REQUESTED' AND o.confirmed_at IS NULL)) AND (CAST(:g AS varchar) IS NULL OR o.governorate_code = CAST(:g AS varchar))"
                + " AND (CAST(:q AS varchar) IS NULL OR o.order_number ILIKE CAST(:q AS varchar) OR o.customer_name_snapshot ILIKE CAST(:q AS varchar) OR o.customer_phone_snapshot LIKE CAST(:q AS varchar)"
                + "      OR EXISTS (SELECT 1 FROM commerce.order_items oi WHERE oi.order_id = o.id AND (oi.product_name_snapshot ILIKE CAST(:q AS varchar) OR oi.sku_snapshot ILIKE CAST(:q AS varchar))))"
                + " AND (CAST(:ch AS varchar) IS NULL OR o.channel = CAST(:ch AS varchar))"
                + " AND (CAST(:pm AS varchar) IS NULL OR o.payment_method = CAST(:pm AS varchar)) AND (CAST(:ps AS varchar) IS NULL OR o.payment_status = CAST(:ps AS varchar))"
                + " AND (CAST(:mn AS bigint) IS NULL OR o.total_minor >= CAST(:mn AS bigint)) AND (CAST(:mx AS bigint) IS NULL OR o.total_minor <= CAST(:mx AS bigint))"
                + " AND (CAST(:rk AS varchar) IS NULL OR o.risk_level = CAST(:rk AS varchar)) AND (CAST(:src AS varchar) IS NULL OR o.source = CAST(:src AS varchar))"
                + " AND (CAST(:br AS uuid) IS NULL OR EXISTS (SELECT 1 FROM commerce.order_items oi WHERE oi.order_id = o.id AND oi.branch_id = CAST(:br AS uuid)))"
                + switch (discount) {
                    case "COUPON" -> " AND o.coupon_code IS NOT NULL";
                    case "OFFER" -> " AND jsonb_array_length(coalesce(o.applied_offers, '[]'::jsonb)) > 0";
                    case "ANY" -> " AND o.discount_minor > 0";
                    case "NONE" -> " AND o.discount_minor = 0";
                    default -> "";
                }
                + " AND " + com.platform.shared.DateRange.sql("o.created_at", "t");
        String order = switch (f.sort() == null ? "" : f.sort().toUpperCase()) {
            case "OLDEST" -> "o.created_at ASC";
            case "TOTAL_DESC" -> "o.total_minor DESC, o.created_at DESC";
            case "TOTAL_ASC" -> "o.total_minor ASC, o.created_at DESC";
            default -> "o.created_at DESC";
        };
        java.util.function.UnaryOperator<org.springframework.jdbc.core.simple.JdbcClient.StatementSpec> bind = st -> st
                .param("t", tenantId).param("sn", statuses.size()).param("ss", String.join(",", statuses)).param("u", f.userId()).param("unc", Boolean.TRUE.equals(f.unconfirmed()))
                .param("g", blankUpper(f.governorate())).param("q", like).param("ch", blankUpper(f.channel())).param("pm", blankUpper(f.paymentMethod())).param("ps", blankUpper(f.paymentStatus()))
                .param("mn", f.minTotal()).param("mx", f.maxTotal()).param("rk", blankUpper(f.risk())).param("src", blankUpper(f.source())).param("br", f.branchId())
                .param("from", f.from()).param("to", f.to());
        var sums = bind.apply(jdbc.sql("SELECT count(*) AS n, coalesce(sum(o.total_minor) FILTER (WHERE o.status NOT IN ('CANCELLED','RETURNED','REFUNDED')), 0) AS revenue FROM commerce.orders o WHERE " + where)).query().singleRow();
        long total = ((Number) sums.get("n")).longValue();
        var rows = bind.apply(jdbc.sql("SELECT o.id, o.order_number, o.source, o.channel, o.whatsapp_confirmed_at, o.status, o.payment_status, o.fulfillment_status, o.payment_method, o.currency, o.total_minor, o.discount_minor, o.coupon_code, "
                        + "o.customer_name_snapshot, o.customer_phone_snapshot, o.governorate_code, o.confirmed_at, o.courier_name, o.tracking_number, o.created_at, o.risk_level, o.needs_confirmation, "
                        + "(SELECT coalesce(sum(oi.quantity), 0) FROM commerce.order_items oi WHERE oi.order_id = o.id) AS item_count FROM commerce.orders o WHERE " + where + " ORDER BY " + order + " LIMIT :lim OFFSET :off"))
                .param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        Map<String, Object> out = new java.util.LinkedHashMap<>(page.wrap(Rows.camel(rows), total));
        out.put("revenueMinor", sums.get("revenue"));
        return out;
    }

    private static String blankUpper(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }

    /** Orders waiting for the owner to call the customer. */
    public long unconfirmedCount(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND status = 'REQUESTED' AND confirmed_at IS NULL").param("t", tenantId).query(Long.class).single();
    }

    public Map<String, Object> get(UUID tenantId, UUID orderId) {
        var o = jdbc.sql("""
                SELECT id, order_number, source, channel, whatsapp_number, whatsapp_confirmed_at, status, payment_status, fulfillment_status, payment_method, coupon_code, currency, subtotal_minor, discount_minor,
                       shipping_minor, tax_minor, cod_fee_minor, total_minor, customer_name_snapshot, customer_phone_snapshot, shipping_address_snapshot::text AS shipping_address,
                       governorate_code, area, landmark, phone2, eta_min_days, eta_max_days, confirmed_at, courier_name, tracking_number, tracking_url, shipped_at, delivered_at,
                       notes, created_at, updated_at, user_id, risk_score, risk_level, risk_reasons, needs_confirmation
                FROM commerce.orders WHERE id = :o AND tenant_id = :t
                """).param("o", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        Map<String, Object> out = Rows.camel(o);
        out.put("shippingAddress", Rows.json(o.get("shipping_address")));
        out.put("items", Rows.camel(jdbc.sql("SELECT product_id, variant_id, sku_snapshot, product_name_snapshot, variant_name_snapshot, unit_price_minor, quantity, discount_minor, line_total_minor, note FROM commerce.order_items WHERE order_id = :o ORDER BY created_at, id")
                .param("o", orderId).query().listOfRows()));
        out.put("history", Rows.camel(jdbc.sql("SELECT from_status, new_status, reason, created_at FROM commerce.order_status_history WHERE order_id = :o ORDER BY created_at, id").param("o", orderId).query().listOfRows()));
        out.put("customerHistory", phoneFlags.stats(tenantId, (String) o.get("customer_phone_snapshot")));
        out.put("payments", Rows.camel(jdbc.sql("SELECT id, kind, method, provider, amount_minor, status, paid_at, original_payment_id FROM commerce.order_payments WHERE order_id = :o ORDER BY created_at").param("o", orderId).query().listOfRows()));
        return out;
    }

    /** A shopper can only ever load their own order. */
    public Map<String, Object> getForCustomer(UUID tenantId, UUID userId, UUID orderId) {
        Map<String, Object> o = get(tenantId, orderId);
        if (userId == null || !userId.equals(o.get("userId"))) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
        o.remove("userId");
        o.remove("customerHistory");   // the shop's private view of a phone number
        return o;
    }

    /** Locks the order for the caller (used by item-level returns, which restock only what came back). */
    Map<String, Object> lockForUpdate(UUID tenantId, UUID orderId) { return lock(tenantId, orderId); }

    /**
     * Marks a delivered order RETURNED when every item has come back through item-level returns. Stock was already restocked line by line,
     * so unlike {@link #apply} this does not touch inventory.
     */
    void markFullyReturned(UUID tenantId, Map<String, Object> o, UUID changedBy, String reason) {
        UUID orderId = (UUID) o.get("id");
        String phone = (String) o.get("customer_phone_snapshot");
        phoneFlags.bump(tenantId, phone, "returned_count");
        loyalty.reverseForReturned(tenantId, orderId, phone);
        jdbc.sql("UPDATE commerce.orders SET status = 'RETURNED', updated_at = now() WHERE id = :o").param("o", orderId).update();
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, :f, 'RETURNED', :c, :r)")
                .param("o", orderId).param("t", tenantId).param("f", o.get("status")).param("c", changedBy).param("r", reason).update();
        events.publishEvent(new OrderEvents.OrderStatusChanged(tenantId, orderId, (UUID) o.get("user_id"), (String) o.get("order_number"), "RETURNED"));
    }

    private Map<String, Object> lock(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT * FROM commerce.orders WHERE id = :o AND tenant_id = :t FOR UPDATE").param("o", orderId).param("t", tenantId).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
    }

}

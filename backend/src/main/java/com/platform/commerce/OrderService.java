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
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("PROCESSING", "CANCELLED"),
            "PROCESSING", Set.of("PACKED", "CANCELLED"),
            "PACKED", Set.of("OUT_FOR_DELIVERY", "CANCELLED"),
            "OUT_FOR_DELIVERY", Set.of("DELIVERED"),
            "DELIVERED", Set.of("RETURN_REQUESTED"),
            "RETURN_REQUESTED", Set.of("RETURNED", "DELIVERED"),
            "RETURNED", Set.of("REFUNDED"));

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public OrderService(JdbcClient jdbc, InventoryService inventory, AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
        this.events = events;
    }

    /** Staff-driven (actor = staff user) or customer-driven (customerUserId set) status change. */
    @Transactional
    public Map<String, Object> changeStatus(UUID tenantId, UUID orderId, String newStatus, UUID actor, String reason, UUID customerUserId) {
        var o = lock(tenantId, orderId);
        if (customerUserId != null) {
            if (!customerUserId.equals(o.get("user_id"))) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
            if (!Set.of("PENDING", "CONFIRMED").contains((String) o.get("status")) || !"CANCELLED".equals(newStatus))
                throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "This order can no longer be cancelled by the customer");
        }
        if ("REFUNDED".equals(newStatus)) throw BusinessException.badRequest("INVALID_STATUS_TRANSITION", "Use the refund endpoint");
        apply(tenantId, o, newStatus, actor != null ? actor : customerUserId, reason);
        audit.record(actor, tenantId, "ORDER_STATUS_CHANGED", "order", orderId, "{\"to\":\"" + newStatus + "\"}");
        return get(tenantId, orderId);
    }

    /** Core transition. Caller already holds the row lock. */
    void apply(UUID tenantId, Map<String, Object> o, String newStatus, UUID changedBy, String reason) {
        UUID orderId = (UUID) o.get("id");
        String from = (String) o.get("status");
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(newStatus))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Cannot move an order from " + from + " to " + newStatus);

        var items = jdbc.sql("SELECT variant_id, branch_id, quantity FROM commerce.order_items WHERE order_id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).query().listOfRows();
        switch (newStatus) {
            case "CANCELLED" -> {
                for (var it : items) inventory.release(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                jdbc.sql("UPDATE commerce.order_payments SET status = 'FAILED', updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'PENDING'").param("o", orderId).update();
            }
            case "DELIVERED" -> {
                if ("RETURN_REQUESTED".equals(from)) break;   // return rejected: goods stay sold
                for (var it : items) inventory.sell(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
                if ("CASH_ON_DELIVERY".equals(o.get("payment_method"))) {
                    jdbc.sql("UPDATE commerce.order_payments SET status = 'SUCCEEDED', paid_at = now(), updated_at = now() WHERE order_id = :o AND kind = 'PAYMENT'").param("o", orderId).update();
                    jdbc.sql("UPDATE commerce.orders SET payment_status = 'PAID' WHERE id = :o").param("o", orderId).update();
                }
            }
            case "RETURNED" -> {
                for (var it : items) inventory.restock(tenantId, (UUID) it.get("branch_id"), (UUID) it.get("variant_id"), ((Number) it.get("quantity")).intValue(), orderId);
            }
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
            case "PROCESSING", "PACKED" -> "PROCESSING";
            case "OUT_FOR_DELIVERY" -> "SHIPPED";
            case "DELIVERED" -> "FULFILLED";
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
        if ("PENDING".equals(o.get("status"))) apply(tenantId, o, "CONFIRMED", null, "Card payment received");
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
    public Map<String, Object> refund(UUID tenantId, UUID actor, UUID orderId, long amountMinor, String reason) {
        var o = lock(tenantId, orderId);
        if (!Set.of("PAID", "PARTIALLY_REFUNDED").contains((String) o.get("payment_status")))
            throw BusinessException.badRequest("REFUND_NOT_ALLOWED", "This order has no paid amount to refund");
        var pay = jdbc.sql("SELECT id, amount_minor, provider, method, currency FROM commerce.order_payments WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'SUCCEEDED'")
                .param("o", orderId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.badRequest("REFUND_NOT_ALLOWED", "No successful payment"));
        long paid = ((Number) pay.get("amount_minor")).longValue();
        long refunded = jdbc.sql("SELECT coalesce(sum(amount_minor),0) FROM commerce.order_payments WHERE order_id = :o AND kind = 'REFUND' AND status = 'SUCCEEDED'").param("o", orderId).query(Long.class).single();
        if (amountMinor <= 0 || refunded + amountMinor > paid)
            throw BusinessException.badRequest("REFUND_EXCEEDS_PAID", "Refund exceeds the paid amount");
        // A refund is always linked to the original payment (spec 73).
        jdbc.sql("""
                INSERT INTO commerce.order_payments (order_id, tenant_id, kind, original_payment_id, provider, method, amount_minor, currency, status, idempotency_key)
                VALUES (:o, :t, 'REFUND', :p, :pv, :m, :a, :c, 'SUCCEEDED', :k)
                """).param("o", orderId).param("t", tenantId).param("p", pay.get("id")).param("pv", pay.get("provider")).param("m", pay.get("method"))
                .param("a", amountMinor).param("c", pay.get("currency")).param("k", "refund-" + UUID.randomUUID()).update();
        boolean full = refunded + amountMinor == paid;
        jdbc.sql("UPDATE commerce.orders SET payment_status = :s, updated_at = now() WHERE id = :o").param("s", full ? "REFUNDED" : "PARTIALLY_REFUNDED").param("o", orderId).update();
        if (full && "RETURNED".equals(o.get("status"))) apply(tenantId, o, "REFUNDED", actor, reason);
        audit.record(actor, tenantId, "REFUND_CREATED", "order", orderId, "{\"amountMinor\":" + amountMinor + "}");
        return get(tenantId, orderId);
    }

    // ---- reads -------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, Page page, String status, UUID userId) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar)) AND (CAST(:u AS uuid) IS NULL OR user_id = CAST(:u AS uuid))")
                .param("t", tenantId).param("s", status).param("u", userId).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT id, order_number, status, payment_status, fulfillment_status, payment_method, currency, total_minor, customer_name_snapshot, created_at
                FROM commerce.orders WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar)) AND (CAST(:u AS uuid) IS NULL OR user_id = CAST(:u AS uuid))
                ORDER BY created_at DESC LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("s", status).param("u", userId).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    public Map<String, Object> get(UUID tenantId, UUID orderId) {
        var o = jdbc.sql("""
                SELECT id, order_number, status, payment_status, fulfillment_status, payment_method, coupon_code, currency, subtotal_minor, discount_minor,
                       shipping_minor, tax_minor, total_minor, customer_name_snapshot, customer_phone_snapshot, shipping_address_snapshot::text AS shipping_address,
                       notes, created_at, updated_at, user_id
                FROM commerce.orders WHERE id = :o AND tenant_id = :t
                """).param("o", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        Map<String, Object> out = Rows.camel(o);
        out.put("shippingAddress", Rows.json(o.get("shipping_address")));
        out.put("items", Rows.camel(jdbc.sql("SELECT product_id, variant_id, sku_snapshot, product_name_snapshot, variant_name_snapshot, unit_price_minor, quantity, discount_minor, line_total_minor FROM commerce.order_items WHERE order_id = :o ORDER BY created_at, id")
                .param("o", orderId).query().listOfRows()));
        out.put("history", Rows.camel(jdbc.sql("SELECT from_status, new_status, reason, created_at FROM commerce.order_status_history WHERE order_id = :o ORDER BY created_at, id").param("o", orderId).query().listOfRows()));
        out.put("payments", Rows.camel(jdbc.sql("SELECT id, kind, method, provider, amount_minor, status, paid_at, original_payment_id FROM commerce.order_payments WHERE order_id = :o ORDER BY created_at").param("o", orderId).query().listOfRows()));
        return out;
    }

    /** A shopper can only ever load their own order. */
    public Map<String, Object> getForCustomer(UUID tenantId, UUID userId, UUID orderId) {
        Map<String, Object> o = get(tenantId, orderId);
        if (!userId.equals(o.get("userId"))) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
        o.remove("userId");
        return o;
    }

    private Map<String, Object> lock(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT * FROM commerce.orders WHERE id = :o AND tenant_id = :t FOR UPDATE").param("o", orderId).param("t", tenantId).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
    }

}

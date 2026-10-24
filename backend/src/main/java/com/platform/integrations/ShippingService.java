package com.platform.integrations;

import com.platform.audit.AuditService;
import com.platform.commerce.OrderService;
import com.platform.commerce.StoreFulfillmentService;
import com.platform.integrations.carriers.CarrierAdapter;
import com.platform.integrations.carriers.Governorates;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ships orders with the carrier accounts the owner connected: builds the parcel from the order, creates the shipment with the carrier, saves the tracking number on the order
 * (shop order or channel order) and follows the parcel until it is delivered, moving the order along.
 */
@Service
public class ShippingService {
    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);
    public record ShipReq(UUID orderId, UUID channelOrderId, UUID accountId, Double weightKg, Integer pieces, String description, String notes, Long codMinor, Boolean markShipped) {}

    private final JdbcClient jdbc;
    private final IntegrationService integrations;
    private final OrderService orders;
    private final StoreFulfillmentService fulfilment;
    private final ChannelOrderService channelOrders;
    private final AuditService audit;
    private final boolean scheduler;

    public ShippingService(JdbcClient jdbc, IntegrationService integrations, OrderService orders, StoreFulfillmentService fulfilment, ChannelOrderService channelOrders, AuditService audit,
                           @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.channelOrders = channelOrders;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    // ---- creating ----------------------------------------------------------------------------------------------

    public Map<String, Object> ship(UUID tenantId, UUID actor, ShipReq r) {
        if ((r.orderId() == null) == (r.channelOrderId() == null)) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose one order to ship");
        var account = integrations.row(tenantId, r.accountId());
        Providers.Def d = Providers.get((String) account.get("provider"));
        if (!Providers.CARRIER.equals(account.get("kind"))) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose a carrier account");
        String country = (String) account.get("country");
        String currency = Providers.currencyOf(country);
        CarrierAdapter.Party sender = sender(tenantId, country);
        CarrierAdapter.Party receiver;
        String reference;
        long cod;
        String description = r.description() == null || r.description().isBlank() ? null : r.description().trim();
        if (r.orderId() != null) {
            var o = jdbc.sql("SELECT order_number, customer_name_snapshot, customer_phone_snapshot, shipping_address_snapshot::text AS addr, governorate_code, area, landmark, payment_method, payment_status, total_minor, status, currency FROM commerce.orders WHERE id = :i AND tenant_id = :t")
                    .param("i", r.orderId()).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
            if (List.of("CANCELLED", "RETURNED", "REFUNDED", "PENDING").contains(o.get("status"))) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This order cannot be shipped now");
            Map<String, Object> a = Rows.json(o.get("addr"));
            String gov = (String) o.get("governorate_code");
            String city = "EG".equals(country) ? Governorates.english(gov, str(a.get("city"))) : str(a.get("city"));
            String line2 = join(str(o.get("area")), str(o.get("landmark")));
            receiver = new CarrierAdapter.Party((String) o.get("customer_name_snapshot"), (String) o.get("customer_phone_snapshot"), null, str(a.get("addressLine1")), line2, city, gov, country, null);
            reference = (String) o.get("order_number");
            boolean cash = "CASH_ON_DELIVERY".equals(o.get("payment_method")) && !"PAID".equals(o.get("payment_status"));
            cod = r.codMinor() != null ? r.codMinor() : cash ? ((Number) o.get("total_minor")).longValue() : 0;
            currency = o.get("currency") == null ? currency : (String) o.get("currency");
        } else {
            var o = jdbc.sql("SELECT number, customer_name, customer_phone, customer_email, address::text AS addr, stage FROM integrations.orders WHERE id = :i AND tenant_id = :t").param("i", r.channelOrderId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
            if (List.of("CANCELLED", "RETURNED", "DELIVERED").contains(o.get("stage"))) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This order cannot be shipped now");
            Map<String, Object> a = Rows.json(o.get("addr"));
            receiver = new CarrierAdapter.Party(str(o.get("customer_name")).isEmpty() ? "Customer" : (String) o.get("customer_name"), (String) o.get("customer_phone"), (String) o.get("customer_email"), str(a.get("line1")), str(a.get("line2")), str(a.get("city")), str(a.get("region")), country, str(a.get("zip")));
            reference = (String) o.get("number");
            cod = r.codMinor() == null ? 0 : r.codMinor();   // channel orders are normally prepaid: the owner says if cash is collected
        }
        if (cod < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Cash to collect cannot be negative");
        CarrierAdapter.ShipRequest req = new CarrierAdapter.ShipRequest(reference, sender, receiver, cod, currency, r.weightKg() == null || r.weightKg() <= 0 ? 1.0 : Math.min(r.weightKg(), 500), r.pieces() == null ? 1 : Math.max(1, Math.min(r.pieces(), 50)),
                description, r.notes());
        CarrierAdapter.Created created;
        try {
            created = integrations.carrier(d.id()).create(integrations.ctxOf(account), req);
        } catch (HttpGateway.ApiException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CARRIER_REJECTED", e.getMessage());
        }
        UUID id = jdbc.sql("""
                INSERT INTO integrations.shipments (tenant_id, account_id, order_id, channel_order_id, tracking_number, label_url, status, cod_minor, created_by) VALUES (:t, :a, :o, :c, :tn, :l, 'CREATED', :cod, :u) RETURNING id
                """).param("t", tenantId).param("a", r.accountId()).param("o", r.orderId()).param("c", r.channelOrderId()).param("tn", created.trackingNumber()).param("l", created.labelUrl()).param("cod", cod).param("u", actor).query(UUID.class).single();
        audit.record(actor, tenantId, "SHIPMENT_CREATED", "shipment", id, "{\"carrier\":\"" + d.id() + "\"}");
        if (r.orderId() != null) {
            fulfilment.setTracking(tenantId, actor, r.orderId(), d.name(), created.trackingNumber(), "");
            if (!Boolean.FALSE.equals(r.markShipped())) moveShopOrder(tenantId, actor, r.orderId(), "SHIPPED");
        } else if (!Boolean.FALSE.equals(r.markShipped())) {
            markChannelShipped(tenantId, actor, r.channelOrderId(), d.name(), created.trackingNumber());
        }
        return get(tenantId, id);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static String join(String a, String b) { return (a == null || a.isEmpty()) ? b : (b == null || b.isEmpty()) ? a : a + ", " + b; }

    /** The sender is the first open branch, else the shop's own details. */
    private CarrierAdapter.Party sender(UUID tenantId, String country) {
        var b = jdbc.sql("SELECT name, phone, address_line1, address_line2, city, governorate_code FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        var sp = jdbc.sql("SELECT coalesce(store_name, '') AS name, support_phone, support_email, address_text FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(Map.of());
        String shopName = str(sp.get("name")).isEmpty() ? "Shop" : str(sp.get("name"));
        String phone = b != null && b.get("phone") != null ? (String) b.get("phone") : (String) sp.get("support_phone");
        String line = b != null && !str(b.get("address_line1")).isEmpty() ? str(b.get("address_line1")) : str(sp.get("address_text"));
        String city = b != null && !str(b.get("city")).isEmpty() ? str(b.get("city")) : b != null ? Governorates.english((String) b.get("governorate_code"), "") : "";
        if (line.isEmpty() || city.isEmpty() || phone == null || phone.isBlank()) throw BusinessException.badRequest("SENDER_INCOMPLETE", "Add your pickup address, city and phone in Settings > Branches before shipping");
        return new CarrierAdapter.Party(shopName, phone, (String) sp.get("support_email"), line, b == null ? null : (String) b.get("address_line2"), city, b == null ? null : (String) b.get("governorate_code"), country, null);
    }

    /** Steps the shop order along its normal path (requested, preparing, shipped); an order that cannot move is left as it is. */
    private void moveShopOrder(UUID tenantId, UUID actor, UUID orderId, String target) {
        try {
            String status = jdbc.sql("SELECT status FROM commerce.orders WHERE id = :i AND tenant_id = :t").param("i", orderId).param("t", tenantId).query(String.class).single();
            if ("REQUESTED".equals(status) && !"PREPARING".equals(target)) { orders.changeStatus(tenantId, orderId, "PREPARING", actor, "Shipment created", null); status = "PREPARING"; }
            if ("PREPARING".equals(status) && "SHIPPED".equals(target)) orders.changeStatus(tenantId, orderId, "SHIPPED", actor, "Handed to the carrier", null);
            if ("SHIPPED".equals(target) || "ARRIVED".equals(target)) {
                if ("ARRIVED".equals(target)) {
                    String now = jdbc.sql("SELECT status FROM commerce.orders WHERE id = :i").param("i", orderId).query(String.class).single();
                    if ("SHIPPED".equals(now)) orders.changeStatus(tenantId, orderId, "ARRIVED", null, "Delivered (carrier)", null);
                }
            }
        } catch (BusinessException e) {
            log.debug("Order {} not moved: {}", orderId, e.getMessage());
        }
    }

    /** Tells the platform the order shipped (with the tracking number) when it has an action for that. */
    private void markChannelShipped(UUID tenantId, UUID actor, UUID channelOrderId, String carrier, String tracking) {
        try {
            var detail = channelOrders.detail(tenantId, channelOrderId);
            for (Object a : (List<?>) detail.get("actions")) {
                Map<?, ?> m = (Map<?, ?>) a;
                String key = String.valueOf(m.get("key"));
                if (List.of("fulfill", "ship", "complete", "delivering").contains(key)) { channelOrders.act(tenantId, actor, channelOrderId, key, Map.of("trackingNumber", tracking, "carrier", carrier)); return; }
            }
            jdbc.sql("UPDATE integrations.orders SET tracking_number = :t, carrier = :c WHERE id = :i").param("t", tracking).param("c", carrier).param("i", channelOrderId).update();
        } catch (BusinessException e) {
            log.info("Channel order not marked shipped: {}", e.getMessage());   // the shipment exists; the owner can mark it on the platform
        }
    }

    // ---- following ---------------------------------------------------------------------------------------------

    public Map<String, Object> refresh(UUID tenantId, UUID id) {
        var s = jdbc.sql("SELECT s.id, s.account_id, s.tracking_number, s.status, s.order_id, s.channel_order_id FROM integrations.shipments s WHERE s.id = :i AND s.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Shipment not found"));
        follow(s);
        return get(tenantId, id);
    }

    private void follow(Map<String, Object> s) {
        var account = integrations.rowAny((UUID) s.get("account_id"));
        UUID tenantId = (UUID) account.get("tenant_id");
        CarrierAdapter.Tracking t;
        try {
            t = integrations.carrier((String) account.get("provider")).track(integrations.ctxOf(account), (String) s.get("tracking_number"));
        } catch (HttpGateway.ApiException | BusinessException e) {
            log.debug("Tracking failed: {}", e.getMessage());
            return;
        }
        if (t == null) return;
        jdbc.sql("UPDATE integrations.shipments SET status = :st, last_event = :e, last_event_at = coalesce(:at, now()), updated_at = now() WHERE id = :i").param("st", t.stage()).param("e", t.lastEvent() == null ? t.status() : t.lastEvent())
                .param("at", t.at() == null ? null : java.sql.Timestamp.from(t.at())).param("i", s.get("id")).update();
        if ("DELIVERED".equals(t.stage())) {
            if (s.get("order_id") != null) moveShopOrder(tenantId, null, (UUID) s.get("order_id"), "ARRIVED");
            if (s.get("channel_order_id") != null) jdbc.sql("UPDATE integrations.orders SET stage = 'DELIVERED' WHERE id = :i AND stage IN ('SHIPPED', 'PROCESSING')").param("i", s.get("channel_order_id")).update();
        }
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void followAll() {
        if (!scheduler) return;
        for (var s : jdbc.sql("SELECT id, account_id, tracking_number, status, order_id, channel_order_id FROM integrations.shipments WHERE status NOT IN ('DELIVERED', 'RETURNED', 'CANCELLED') AND created_at > now() - interval '60 days' AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = integrations.shipments.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) ORDER BY updated_at LIMIT 100").query().listOfRows()) {
            try { follow(s); } catch (RuntimeException e) { log.debug("Follow failed: {}", e.getMessage()); }
        }
    }

    public Map<String, Object> cancel(UUID tenantId, UUID actor, UUID id) {
        var s = jdbc.sql("SELECT account_id, tracking_number, status FROM integrations.shipments WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Shipment not found"));
        if (List.of("DELIVERED", "RETURNED", "CANCELLED").contains(s.get("status"))) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This shipment can no longer be cancelled");
        var account = integrations.row(tenantId, (UUID) s.get("account_id"));
        try {
            integrations.carrier((String) account.get("provider")).cancel(integrations.ctxOf(account), (String) s.get("tracking_number"));
        } catch (HttpGateway.ApiException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CARRIER_REJECTED", e.getMessage());
        }
        jdbc.sql("UPDATE integrations.shipments SET status = 'CANCELLED', updated_at = now() WHERE id = :i").param("i", id).update();
        audit.record(actor, tenantId, "SHIPMENT_CANCELLED", "shipment", id, null);
        return get(tenantId, id);
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT s.id, s.account_id, a.provider, a.country, s.order_id, o.order_number, s.channel_order_id, co.number AS channel_order_number, s.tracking_number, s.label_url, s.status, s.cod_minor, s.last_event, s.last_event_at, s.created_at
            FROM integrations.shipments s JOIN integrations.accounts a ON a.id = s.account_id LEFT JOIN commerce.orders o ON o.id = s.order_id LEFT JOIN integrations.orders co ON co.id = s.channel_order_id
            """;

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return decorate(Rows.camel(jdbc.sql(SELECT + " WHERE s.id = :i AND s.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Shipment not found"))));
    }

    public Map<String, Object> list(UUID tenantId, UUID orderId, UUID channelOrderId, String q, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = "s.tenant_id = :t AND (CAST(:o AS uuid) IS NULL OR s.order_id = CAST(:o AS uuid)) AND (CAST(:c AS uuid) IS NULL OR s.channel_order_id = CAST(:c AS uuid)) AND (CAST(:q AS varchar) IS NULL OR s.tracking_number ILIKE CAST(:q AS varchar) OR o.order_number ILIKE CAST(:q AS varchar) OR co.number ILIKE CAST(:q AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM integrations.shipments s LEFT JOIN commerce.orders o ON o.id = s.order_id LEFT JOIN integrations.orders co ON co.id = s.channel_order_id WHERE " + where).param("t", tenantId).param("o", orderId).param("c", channelOrderId).param("q", like).query(Long.class).single();
        var rows = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY s.created_at DESC LIMIT :lim OFFSET :off").param("t", tenantId).param("o", orderId).param("c", channelOrderId).param("q", like).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows).stream().map(this::decorate).toList(), total);
    }

    private Map<String, Object> decorate(Map<String, Object> m) {
        Providers.Def d = Providers.get((String) m.get("provider"));
        m.put("carrier", d == null ? m.get("provider") : d.name());
        m.put("trackingUrl", trackingUrl((String) m.get("provider"), (String) m.get("trackingNumber")));
        return m;
    }

    static String trackingUrl(String provider, String tn) {
        String e = java.net.URLEncoder.encode(tn == null ? "" : tn, java.nio.charset.StandardCharsets.UTF_8);
        return switch (provider) {
            case "ARAMEX" -> "https://www.aramex.com/track/results?ShipmentNumber=" + e;
            case "BOSTA" -> "https://bosta.co/tracking-shipment/?lang=en&shipment-number=" + e;
            default -> null;
        };
    }
}

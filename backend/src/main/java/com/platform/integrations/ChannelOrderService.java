package com.platform.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Orders that customers placed on the connected channels: browse them, open one, and act on it on the platform (accept, ship with tracking, cancel...). */
@Service
public class ChannelOrderService {
    private final JdbcClient jdbc;
    private final IntegrationService integrations;
    private final AuditService audit;

    public ChannelOrderService(JdbcClient jdbc, IntegrationService integrations, AuditService audit) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.audit = audit;
    }

    private static final String FILTER = """
            o.tenant_id = :t AND (CAST(:a AS uuid) IS NULL OR o.account_id = CAST(:a AS uuid)) AND (CAST(:g AS varchar) IS NULL OR o.stage = CAST(:g AS varchar))
            AND (CAST(:q AS varchar) IS NULL OR o.number ILIKE CAST(:q AS varchar) OR o.customer_name ILIKE CAST(:q AS varchar) OR o.customer_phone ILIKE CAST(:q AS varchar) OR o.customer_email ILIKE CAST(:q AS varchar))
            AND (CAST(:from AS timestamptz) IS NULL OR o.placed_at >= CAST(:from AS timestamptz)) AND (CAST(:to AS timestamptz) IS NULL OR o.placed_at < CAST(:to AS timestamptz))
            """;

    public Map<String, Object> list(UUID tenantId, UUID accountId, String stage, String q, String from, String to, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        long total = bind(jdbc.sql("SELECT count(*) FROM integrations.orders o WHERE " + FILTER), tenantId, accountId, stage, like, from, to).query(Long.class).single();
        var rows = bind(jdbc.sql("""
                SELECT o.id, o.account_id, a.provider, a.label AS account_label, a.country, o.external_id, o.number, o.status, o.stage, o.total_minor, o.currency, o.customer_name, o.customer_phone,
                       jsonb_array_length(o.items) AS item_count, o.tracking_number, o.carrier, o.placed_at
                FROM integrations.orders o JOIN integrations.accounts a ON a.id = o.account_id WHERE %s ORDER BY o.placed_at DESC NULLS LAST, o.id LIMIT :lim OFFSET :off
                """.formatted(FILTER)), tenantId, accountId, stage, like, from, to).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec s, UUID t, UUID a, String g, String q, String from, String to) {
        return s.param("t", t).param("a", a).param("g", g == null || g.isBlank() ? null : g).param("q", q).param("from", from == null || from.isBlank() ? null : from).param("to", to == null || to.isBlank() ? null : to);
    }

    /** Orders per stage, for the tabs. */
    public Map<String, Object> summary(UUID tenantId, UUID accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : List.of("NEW", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED")) out.put(s, 0L);
        for (var r : jdbc.sql("SELECT stage, count(*) AS n FROM integrations.orders WHERE tenant_id = :t AND (CAST(:a AS uuid) IS NULL OR account_id = CAST(:a AS uuid)) GROUP BY stage").param("t", tenantId).param("a", accountId).query().listOfRows()) out.put((String) r.get("stage"), r.get("n"));
        return out;
    }

    public Map<String, Object> detail(UUID tenantId, UUID id) {
        var o = jdbc.sql("""
                SELECT o.id, o.account_id, a.provider, a.label AS account_label, a.country, o.external_id, o.number, o.status, o.stage, o.total_minor, o.currency, o.customer_name, o.customer_phone, o.customer_email,
                       o.address::text AS address, o.items::text AS items, o.tracking_number, o.carrier, o.placed_at, o.synced_at
                FROM integrations.orders o JOIN integrations.accounts a ON a.id = o.account_id WHERE o.id = :i AND o.tenant_id = :t
                """).param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(o));
        m.put("address", Rows.json(o.get("address")));
        m.put("items", Rows.jsonList(o.get("items")));
        Providers.Def d = Providers.get((String) o.get("provider"));
        m.put("providerName", d == null ? o.get("provider") : d.name());
        List<Map<String, Object>> actions = new ArrayList<>();
        if (d != null) {
            Ext.Order eo = new Ext.Order((String) o.get("external_id"), (String) o.get("number"), (String) o.get("status"), (String) o.get("stage"), ((Number) o.get("total_minor")).longValue(), (String) o.get("currency"), null, null, null, Map.of(), List.of(), null, null, null);
            for (Ext.Action a : integrations.adapter(d.id()).actions(eo)) actions.add(Map.of("key", a.key(), "label", a.label(), "needsTracking", a.needsTracking()));
        }
        m.put("actions", actions);
        m.put("shipments", Rows.camel(jdbc.sql("SELECT s.id, s.tracking_number, s.status, s.label_url, s.last_event, s.last_event_at, a.provider AS carrier FROM integrations.shipments s JOIN integrations.accounts a ON a.id = s.account_id WHERE s.channel_order_id = :i ORDER BY s.created_at DESC")
                .param("i", id).query().listOfRows()));
        return m;
    }

    public Map<String, Object> act(UUID tenantId, UUID actor, UUID id, String action, Map<String, String> params) {
        var o = jdbc.sql("SELECT o.external_id, o.stage, o.account_id FROM integrations.orders o WHERE o.id = :i AND o.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        var account = integrations.row(tenantId, (UUID) o.get("account_id"));
        try {
            integrations.adapter((String) account.get("provider")).act(integrations.ctxOf(account), (String) o.get("external_id"), action, params == null ? Map.of() : params);
        } catch (HttpGateway.ApiException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CHANNEL_REJECTED", e.getMessage());
        }
        String stage = switch (action) {
            case "fulfill", "ship", "delivering" -> "SHIPPED"; case "cancel", "canceled" -> "CANCELLED"; case "processing", "in_progress" -> "PROCESSING"; case "complete", "delivered" -> "DELIVERED"; default -> (String) o.get("stage");
        };
        String tracking = params == null ? null : params.get("trackingNumber");
        jdbc.sql("UPDATE integrations.orders SET stage = :s, tracking_number = coalesce(:tr, tracking_number), carrier = coalesce(:ca, carrier), synced_at = now() WHERE id = :i").param("s", stage)
                .param("tr", tracking == null || tracking.isBlank() ? null : tracking).param("ca", params == null || params.get("carrier") == null || params.get("carrier").isBlank() ? null : params.get("carrier")).param("i", id).update();
        audit.record(actor, tenantId, "CHANNEL_ORDER_ACTION", "integration_order", id, "{\"action\":\"" + action.replaceAll("[^a-z_]", "") + "\"}");
        return detail(tenantId, id);
    }

    /** Customers of the channels, built from their orders: who bought, how often and for how much. */
    public Map<String, Object> customers(UUID tenantId, UUID accountId, String q, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String key = "coalesce(nullif(o.customer_phone, ''), nullif(o.customer_email, ''), o.customer_name, o.number)";
        String where = "o.tenant_id = :t AND (CAST(:a AS uuid) IS NULL OR o.account_id = CAST(:a AS uuid)) AND (CAST(:q AS varchar) IS NULL OR o.customer_name ILIKE CAST(:q AS varchar) OR o.customer_phone ILIKE CAST(:q AS varchar) OR o.customer_email ILIKE CAST(:q AS varchar))";
        long total = jdbc.sql("SELECT count(DISTINCT " + key + ") FROM integrations.orders o WHERE " + where).param("t", tenantId).param("a", accountId).param("q", like).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT %s AS ref, max(o.customer_name) AS name, max(o.customer_phone) AS phone, max(o.customer_email) AS email, count(*) AS orders,
                       coalesce(sum(o.total_minor) FILTER (WHERE o.stage NOT IN ('CANCELLED', 'RETURNED')), 0) AS spent_minor, max(o.placed_at) AS last_order_at, array_agg(DISTINCT a.provider) AS channels, max(o.currency) AS currency
                FROM integrations.orders o JOIN integrations.accounts a ON a.id = o.account_id WHERE %s GROUP BY 1 ORDER BY max(o.placed_at) DESC NULLS LAST LIMIT :lim OFFSET :off
                """.formatted(key, where)).param("t", tenantId).param("a", accountId).param("q", like).param("lim", page.pageSize()).param("off", page.offset()).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ref", rs.getString("ref")); m.put("name", rs.getString("name")); m.put("phone", rs.getString("phone")); m.put("email", rs.getString("email"));
                    m.put("orders", rs.getLong("orders")); m.put("spentMinor", rs.getLong("spent_minor")); m.put("currency", rs.getString("currency"));
                    var ts = rs.getTimestamp("last_order_at");
                    m.put("lastOrderAt", ts == null ? null : ts.toInstant());
                    try { m.put("channels", List.of((String[]) rs.getArray("channels").getArray())); } catch (java.sql.SQLException e) { m.put("channels", List.of()); }
                    return m;
                }).list();
        return page.wrap(rows, total);
    }
}

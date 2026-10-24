package com.platform.commerce;

import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The shop's read-only public API (X-Api-Key on the shop's own address): orders, one order by number, and the product list with stock. */
@RestController
@RequestMapping("/api/v1/public")
@PreAuthorize("hasAuthority('api.read')")
public class PublicApiController {
    private final JdbcClient jdbc;
    private final FeedService feed;

    public PublicApiController(JdbcClient jdbc, FeedService feed) {
        this.jdbc = jdbc;
        this.feed = feed;
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status, @RequestParam(required = false) java.time.Instant since, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        var t = StoreContext.tenantId();
        Page p = Page.of(page, pageSize);
        java.sql.Timestamp from = since == null ? null : java.sql.Timestamp.from(since);
        long total = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar)) AND (CAST(:f AS timestamptz) IS NULL OR created_at >= CAST(:f AS timestamptz))").param("t", t).param("s", status).param("f", from).query(Long.class).single();
        var rows = jdbc.sql("SELECT order_number, status, payment_status, payment_method, source, channel, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, customer_name_snapshot AS customer_name, customer_phone_snapshot AS customer_phone, governorate_code, tracking_number, created_at, updated_at "
                + "FROM commerce.orders WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar)) AND (CAST(:f AS timestamptz) IS NULL OR created_at >= CAST(:f AS timestamptz)) ORDER BY created_at DESC LIMIT :l OFFSET :o")
                .param("t", t).param("s", status).param("f", from).param("l", p.pageSize()).param("o", p.offset()).query().listOfRows();
        return p.wrap(Rows.camel(rows), total);
    }

    @GetMapping("/orders/{number}")
    public Map<String, Object> order(@PathVariable String number) {
        var t = StoreContext.tenantId();
        var o = jdbc.sql("SELECT id, order_number, status, payment_status, payment_method, source, channel, currency, subtotal_minor, discount_minor, shipping_minor, total_minor, customer_name_snapshot AS customer_name, customer_phone_snapshot AS customer_phone, governorate_code, area, tracking_number, created_at, updated_at FROM commerce.orders WHERE tenant_id = :t AND order_number = :n")
                .param("t", t).param("n", number).query().listOfRows().stream().findFirst().orElseThrow(() -> com.platform.shared.BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(o));
        m.remove("id");
        m.put("items", Rows.camel(jdbc.sql("SELECT sku_snapshot AS sku, product_name_snapshot AS name, variant_name_snapshot AS variant, quantity, unit_price_minor, line_total_minor FROM commerce.order_items WHERE order_id = :o ORDER BY created_at").param("o", o.get("id")).query().listOfRows()));
        return m;
    }

    @GetMapping("/products")
    public Map<String, Object> products() { return feed.feed(StoreContext.tenantId()); }
}

package com.platform.open;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** The platform admin can hide a shop from the Open Store (every live shop is listed by default, for free). */
@RestController
@RequestMapping("/api/v1/platform/open")
public class OpenAdminController {
    public record ShopSettings(Boolean listed) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public OpenAdminController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/shops")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> shops(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        Page pg = Page.of(page, pageSize);
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = "t.tenant_type = 'STORE' AND (CAST(:like AS varchar) IS NULL OR t.name ILIKE CAST(:like AS varchar) OR t.slug ILIKE CAST(:like AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE " + where).param("like", like).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT t.id, t.slug, t.name, t.status, sp.open_listed,
                  (SELECT count(*) FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS products,
                  (SELECT count(*) FROM commerce.orders o WHERE o.tenant_id = t.id AND o.source = 'OPEN') AS open_orders
                FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE %s ORDER BY t.name LIMIT :lim OFFSET :off
                """.formatted(where)).param("like", like).param("lim", pg.pageSize()).param("off", pg.offset()).query().listOfRows();
        return pg.wrap(Rows.camel(rows), total);
    }

    @PutMapping("/shops/{tenantId}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID tenantId, @RequestBody ShopSettings s, Authentication a) {
        int n = jdbc.sql("UPDATE commerce.store_profiles SET open_listed = coalesce(:l, open_listed), updated_at = now() WHERE tenant_id = :t").param("l", s.listed()).param("t", tenantId).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Shop not found");
        audit.record((UUID) a.getPrincipal(), tenantId, "OPEN_STORE_SETTINGS_CHANGED", "store_profile", tenantId, "{\"listed\":" + s.listed() + "}");
        return Map.of("listed", jdbc.sql("SELECT open_listed FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query(Boolean.class).single());
    }
}

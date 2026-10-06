package com.platform.core.tenant;

import com.platform.shared.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantContextController {
    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    public TenantContextController(TenantRepository tenants, JdbcClient jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /** Public: lets the web/mobile client theme itself for the tenant behind this host. */
    @GetMapping({"/context", "/public"})
    public Map<String, Object> context() {
        TenantContext.Current c = TenantContext.require();
        Tenant t = tenants.findById(c.id()).orElseThrow();
        Map<String, Object> branding = jdbc.sql("SELECT primary_color, secondary_color, font_family FROM core.branding WHERE tenant_id = :t")
                .param("t", t.getId()).query().listOfRows().stream().findFirst().orElse(Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("slug", t.getSlug());
        out.put("name", t.getName());
        out.put("type", t.getTenantType());
        out.put("status", t.getStatus());
        out.put("locale", t.getDefaultLocale());
        out.put("currency", t.getDefaultCurrency());
        out.put("timezone", t.getTimezone());
        out.put("host", c.host());
        out.put("branding", branding);
        return out;
    }
}

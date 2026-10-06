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
    private final com.platform.shared.PlatformProperties props;
    private final TenantResolutionFilter resolver;

    public TenantContextController(TenantRepository tenants, JdbcClient jdbc, com.platform.shared.PlatformProperties props, TenantResolutionFilter resolver) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.props = props;
        this.resolver = resolver;
    }

    /** What is behind this host? Lets the web app route: PLATFORM (marketing/signup/admin), TENANT (store or clinic), UNKNOWN. */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(jakarta.servlet.http.HttpServletRequest req) {
        if (TenantContext.get() != null) {
            Map<String, Object> m = new LinkedHashMap<>(context());
            m.put("kind", "TENANT");
            return m;
        }
        String host = resolver.requestHost(req);
        return Map.of("kind", props.isPlatformHost(host) ? "PLATFORM" : "UNKNOWN", "rootDomain", props.rootDomain());
    }

    /** Public: lets the web/mobile client theme itself for the tenant behind this host. */
    @GetMapping({"/context", "/public"})
    public Map<String, Object> context() {
        TenantContext.Current c = TenantContext.require();
        Tenant t = tenants.findById(c.id()).orElseThrow();
        Map<String, Object> branding = jdbc.sql("SELECT primary_color, secondary_color, font_family, logo_file_id FROM core.branding WHERE tenant_id = :t")
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

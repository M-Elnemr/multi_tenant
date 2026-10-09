package com.platform.core.user;

import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Works on any host (including the platform host): the access token is user-wide; permissions are still decided per tenant host. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final JdbcClient jdbc;

    public MeController(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Every store/clinic this person belongs to, so one app login can show several doctors with separate data. */
    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants(Authentication a) {
        // a patient account lists the clinics that registered it (each clinic keeps its own record of them)
        var clinics = Rows.camel(jdbc.sql("""
                SELECT t.id, t.slug, t.name, t.tenant_type AS type, t.status, t.default_locale AS locale,
                       (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary AND d.is_verified) AS host, 'PATIENT' AS roles
                FROM medical.patients p JOIN core.tenants t ON t.id = p.tenant_id
                WHERE p.user_id = :u AND p.status = 'ACTIVE' AND t.status NOT IN ('ARCHIVED','CANCELLED') ORDER BY p.created_at
                """).param("u", (UUID) a.getPrincipal()).query().listOfRows());
        if (!clinics.isEmpty()) return clinics;
        return Rows.camel(jdbc.sql("""
                SELECT t.id, t.slug, t.name, t.tenant_type AS type, t.status, t.default_locale AS locale,
                       (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary AND d.is_verified) AS host,
                       (SELECT string_agg(r.code, ',' ORDER BY r.code) FROM core.membership_roles mr JOIN core.roles r ON r.id = mr.role_id WHERE mr.membership_id = m.id) AS roles
                FROM core.user_tenant_memberships m JOIN core.tenants t ON t.id = m.tenant_id
                WHERE m.user_id = :u AND m.status = 'ACTIVE' AND t.status NOT IN ('ARCHIVED','CANCELLED') ORDER BY m.created_at
                """).param("u", (UUID) a.getPrincipal()).query().listOfRows());
    }
}

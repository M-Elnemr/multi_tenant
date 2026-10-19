package com.platform.core.rbac;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class RbacService {
    private final JdbcClient jdbc;

    public RbacService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void assignTenantRole(UUID membershipId, String roleCode) {
        int n = jdbc.sql("""
                INSERT INTO core.membership_roles (membership_id, role_id)
                SELECT :m, r.id FROM core.roles r WHERE r.code = :code AND r.tenant_id IS NULL AND r.scope = 'TENANT'
                ON CONFLICT DO NOTHING
                """).param("m", membershipId).param("code", roleCode).update();
        if (n == 0 && !roleExists(roleCode)) throw new IllegalArgumentException("Unknown tenant role " + roleCode);
    }

    private boolean roleExists(String code) {
        return jdbc.sql("SELECT count(*) FROM core.roles WHERE code = :c AND scope = 'TENANT' AND tenant_id IS NULL")
                .param("c", code).query(Integer.class).single() > 0;
    }

    public List<String> roleCodes(UUID membershipId) {
        return jdbc.sql("""
                SELECT r.code FROM core.membership_roles mr JOIN core.roles r ON r.id = mr.role_id
                WHERE mr.membership_id = :m ORDER BY r.code
                """).param("m", membershipId).query(String.class).list();
    }

    /** Permission codes the membership holds: the union over its roles plus whatever its per-screen access grants. */
    public Set<String> permissionCodes(UUID membershipId) {
        Set<String> out = jdbc.sql("""
                SELECT DISTINCT p.code FROM core.membership_roles mr
                JOIN core.role_permissions rp ON rp.role_id = mr.role_id
                JOIN core.permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = :m
                """).param("m", membershipId).query(String.class).list().stream().collect(Collectors.toSet());
        Map<String, String> access = screenAccess(membershipId);
        if (!access.isEmpty()) out.addAll(ScreenCatalog.permissions(tenantType(membershipId), access));
        return out;
    }

    private String tenantType(UUID membershipId) {
        return jdbc.sql("""
                SELECT t.tenant_type FROM core.user_tenant_memberships m JOIN core.tenants t ON t.id = m.tenant_id WHERE m.id = :m
                """).param("m", membershipId).query(String.class).optional().orElse("");
    }

    /** The screens an employee was given by hand ({@code screen -> READ|WRITE}); empty for role-based members. */
    public Map<String, String> screenAccess(UUID membershipId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT screen, level FROM core.membership_screen_access WHERE membership_id = :m ORDER BY screen")
                .param("m", membershipId).query((rs, i) -> { out.put(rs.getString(1), rs.getString(2)); return 0; }).list();
        return out;
    }

    /** Replaces the employee's screen access. Screens that are not in the tenant type's catalogue are rejected by the caller. */
    public void setScreenAccess(UUID membershipId, Map<String, String> access) {
        jdbc.sql("DELETE FROM core.membership_screen_access WHERE membership_id = :m").param("m", membershipId).update();
        access.forEach((screen, level) -> jdbc.sql("INSERT INTO core.membership_screen_access (membership_id, screen, level) VALUES (:m, :s, :l)")
                .param("m", membershipId).param("s", screen).param("l", level).update());
    }

    public Set<String> platformPermissionCodes(UUID userId) {
        return jdbc.sql("""
                SELECT DISTINCT p.code FROM core.user_platform_roles ur
                JOIN core.role_permissions rp ON rp.role_id = ur.role_id
                JOIN core.permissions p ON p.id = rp.permission_id
                WHERE ur.user_id = :u
                """).param("u", userId).query(String.class).list().stream().collect(Collectors.toSet());
    }

    /** Support access: a platform owner acts as the owner of any business. Returns the owner role's permissions for the tenant type, or empty without the permission. */
    public Set<String> supportPermissions(UUID userId, String tenantType) {
        if (!platformPermissionCodes(userId).contains("platform.support.access")) return Set.of();
        return jdbc.sql("""
                SELECT DISTINCT p.code FROM core.roles r JOIN core.role_permissions rp ON rp.role_id = r.id JOIN core.permissions p ON p.id = rp.permission_id
                WHERE r.code = :c AND r.tenant_id IS NULL
                """).param("c", supportRole(tenantType)).query(String.class).list().stream().collect(Collectors.toSet());
    }

    public String supportRole(String tenantType) { return "STORE".equals(tenantType) ? "STORE_OWNER" : "CLINIC_OWNER"; }

    public boolean hasSupportAccess(UUID userId) { return platformPermissionCodes(userId).contains("platform.support.access"); }
}

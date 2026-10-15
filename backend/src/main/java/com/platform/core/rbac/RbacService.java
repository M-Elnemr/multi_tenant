package com.platform.core.rbac;

import java.util.List;
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

    /** Permission codes the membership holds (union over its roles). */
    public Set<String> permissionCodes(UUID membershipId) {
        return jdbc.sql("""
                SELECT DISTINCT p.code FROM core.membership_roles mr
                JOIN core.role_permissions rp ON rp.role_id = mr.role_id
                JOIN core.permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = :m
                """).param("m", membershipId).query(String.class).list().stream().collect(Collectors.toSet());
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

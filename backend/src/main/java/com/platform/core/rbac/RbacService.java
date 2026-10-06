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
}

package com.platform.commerce;

import com.platform.core.auth.TenantAutoJoin;
import com.platform.core.rbac.RbacService;
import com.platform.core.user.Membership;
import com.platform.core.user.MembershipRepository;
import com.platform.shared.BusinessException;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer membership of a store. Kept apart from registration so AuthService can depend on it without a cycle. */
@Service
public class StoreMembershipService implements TenantAutoJoin {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final JdbcClient jdbc;

    public StoreMembershipService(MembershipRepository memberships, RbacService rbac, JdbcClient jdbc) {
        this.memberships = memberships;
        this.rbac = rbac;
        this.jdbc = jdbc;
    }

    @Override public boolean supports(String tenantType) { return "STORE".equals(tenantType); }

    @Override
    @Transactional
    public void join(UUID userId, UUID tenantId) {
        Membership m = memberships.findByUserIdAndTenantId(userId, tenantId).orElseGet(Membership::new);
        if (m.getId() != null && m.getStatus() == Membership.Status.ACTIVE) return;
        if (m.getId() != null) throw BusinessException.forbidden("NOT_A_MEMBER", "This account has no access here"); // suspended/removed stays out
        m.setUserId(userId);
        m.setTenantId(tenantId);
        m = memberships.saveAndFlush(m);
        rbac.assignTenantRole(m.getId(), "CUSTOMER");
        ensureCustomer(tenantId, userId);
    }

    public UUID ensureCustomer(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT id FROM commerce.customers WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", userId)
                .query(UUID.class).optional().orElseGet(() -> {
                    for (int i = 0; i < 5; i++) {
                        String number = "C-" + random(6);
                        try {
                            return jdbc.sql("INSERT INTO commerce.customers (tenant_id, user_id, customer_number) VALUES (:t, :u, :n) RETURNING id")
                                    .param("t", tenantId).param("u", userId).param("n", number).query(UUID.class).single();
                        } catch (org.springframework.dao.DuplicateKeyException e) {
                            // customer number collision: retry with a new one
                        }
                    }
                    throw new IllegalStateException("Could not allocate customer number");
                });
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}

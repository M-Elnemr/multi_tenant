package com.platform.core.user;

import com.platform.audit.AuditService;
import com.platform.core.auth.ActivationService;
import com.platform.core.rbac.RbacService;
import com.platform.core.tenant.TenantType;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a doctor / receptionist / store staff member in one step. The person is created INVITED and
 * receives a one-time activation PIN to set their own password; no SMS or email service is needed.
 */
@Service
public class StaffService {
    private static final Map<String, Set<String>> ASSIGNABLE = Map.of(
            "STORE", Set.of("STORE_ADMIN", "STORE_MANAGER", "INVENTORY_MANAGER", "ORDER_MANAGER", "CUSTOMER_SUPPORT", "REPORT_VIEWER"),
            "CLINIC", Set.of("DOCTOR", "CLINIC_ADMIN", "RECEPTIONIST", "NURSE", "LAB_COORDINATOR", "ACCOUNTANT", "REPORT_VIEWER"));

    public record Invited(UUID userId, UUID membershipId, String activationPin, boolean needsActivation) {}

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final ActivationService activation;
    private final AuditService audit;
    private final com.platform.billing.EntitlementService entitlements;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public StaffService(UserRepository users, MembershipRepository memberships, RbacService rbac,
                        ActivationService activation, AuditService audit,
                        com.platform.billing.EntitlementService entitlements, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.entitlements = entitlements;
        this.jdbc = jdbc;
        this.users = users;
        this.memberships = memberships;
        this.rbac = rbac;
        this.activation = activation;
        this.audit = audit;
    }

    @Transactional
    public Invited invite(UUID actor, String firstName, String lastName, String phoneRaw, String email, String role) {
        TenantContext.Current t = TenantContext.require();
        if (!ASSIGNABLE.getOrDefault(t.type(), Set.of()).contains(role))
            throw BusinessException.badRequest("ROLE_NOT_ASSIGNABLE", "This role cannot be assigned here");
        String phone = PhoneNormalizer.normalize(phoneRaw);
        long staffCount = jdbc.sql("""
                SELECT count(DISTINCT m.id) FROM core.user_tenant_memberships m
                JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND r.code NOT IN ('CUSTOMER','PATIENT','GUARDIAN')
                """).param("t", t.id()).query(Long.class).single();
        entitlements.requireCapacity(t.id(), "max_staff", staffCount);
        User u = users.findByPhone(phone).orElse(null);
        boolean needsActivation = false;
        if (u == null) {
            u = new User();
            u.setPhone(phone);
            u.setEmail(email == null || email.isBlank() ? null : email.trim().toLowerCase());
            u.setFirstName(firstName);
            u.setLastName(lastName == null ? "" : lastName);
            u.setStatus(User.Status.INVITED);
            u = users.saveAndFlush(u);
            needsActivation = true;
        } else if (u.getStatus() == User.Status.INVITED) {
            needsActivation = true;
        }
        if (needsActivation && memberships.findByUserId(u.getId()).stream()
                .anyMatch(x -> !x.getTenantId().equals(t.id()) && x.getStatus() == Membership.Status.ACTIVE)) {
            // An un-activated account created by another tenant: handing this tenant a PIN would let it take that account over.
            throw BusinessException.conflict("PHONE_PENDING_ELSEWHERE", "This phone number has a pending account elsewhere");
        }
        Membership m = memberships.findByUserIdAndTenantId(u.getId(), t.id()).orElseGet(Membership::new);
        return finish(actor, t, u, m, role, needsActivation);
    }

    private Invited finish(UUID actor, TenantContext.Current t, User u, Membership m, String role, boolean needsActivation) {
        m.setUserId(u.getId());
        m.setTenantId(t.id());
        m.setStatus(Membership.Status.ACTIVE);
        m = memberships.saveAndFlush(m);
        rbac.assignTenantRole(m.getId(), role);
        String pin = needsActivation ? activation.issue(u.getId(), t.id(), actor) : null;
        audit.record(actor, t.id(), "STAFF_INVITED", "user", u.getId(), "{\"role\":\"" + role + "\"}");
        return new Invited(u.getId(), m.getId(), pin, needsActivation);
    }

    /** Staff regenerates a PIN for someone who forgot their password. Works for any member of this tenant. */
    @Transactional
    public String reissuePin(UUID actor, UUID userId) {
        TenantContext.Current t = TenantContext.require();
        Membership m = memberships.findByUserIdAndTenantId(userId, t.id())
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Member not found"));
        // The password is a global identity: never let one tenant reset an account it shares with others, or an owner.
        boolean shared = memberships.findByUserId(userId).stream()
                .anyMatch(x -> !x.getTenantId().equals(t.id()) && x.getStatus() == Membership.Status.ACTIVE);
        boolean owner = rbac.roleCodes(m.getId()).stream().anyMatch(r -> r.endsWith("_OWNER"));
        if (shared || owner) throw BusinessException.forbidden("PIN_REISSUE_NOT_ALLOWED", "Ask platform support to recover this account");
        String pin = activation.issue(userId, t.id(), actor);
        audit.record(actor, t.id(), "ACTIVATION_PIN_REISSUED", "user", userId, null);
        return pin;
    }
}

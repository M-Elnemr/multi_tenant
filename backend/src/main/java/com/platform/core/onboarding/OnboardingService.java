package com.platform.core.onboarding;

import com.platform.audit.AuditService;
import com.platform.core.auth.AuthService;
import com.platform.core.auth.LoginThrottle;
import com.platform.core.auth.TokenResponse;
import com.platform.core.rbac.RbacService;
import com.platform.core.tenant.DomainService;
import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantDomain;
import com.platform.core.tenant.TenantRepository;
import com.platform.core.tenant.TenantStatus;
import com.platform.core.tenant.TenantType;
import com.platform.core.user.Membership;
import com.platform.core.user.MembershipRepository;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.PlatformProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The one-call "create my store / clinic" flow: tenant + owner + defaults + live subdomain, atomically. */
@Service
public class OnboardingService {
    public record Command(TenantType type, String name, String slug, String ownerFirstName, String ownerLastName,
                          String phone, String email, String password, String locale,
                          List<String> categories, String otherCategory) {}

    public List<Map<String, Object>> categories(TenantType type) {
        return provisioners.stream().filter(p -> p.supports() == type).findFirst().map(TenantProvisioner::categories).orElse(List.of());
    }

    public record Result(Tenant tenant, TenantDomain domain, TokenResponse tokens, boolean replay) {}

    private final TenantRepository tenants;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RbacService rbac;
    private final DomainService domains;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final PlatformProperties props;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final List<TenantProvisioner> provisioners;
    private final LoginThrottle throttle;
    private final com.platform.core.tenant.TenantDirectory directory;
    private final org.springframework.context.ApplicationEventPublisher events;

    public OnboardingService(TenantRepository tenants, UserRepository users, MembershipRepository memberships,
                             RbacService rbac, DomainService domains, AuditService audit, PasswordEncoder encoder,
                             AuthService auth, PlatformProperties props, JdbcClient jdbc, TransactionTemplate tx,
                             List<TenantProvisioner> provisioners, LoginThrottle throttle,
                             org.springframework.context.ApplicationEventPublisher events, com.platform.core.tenant.TenantDirectory directory) {
        this.events = events;
        this.directory = directory;
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.rbac = rbac;
        this.domains = domains;
        this.audit = audit;
        this.encoder = encoder;
        this.auth = auth;
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.provisioners = provisioners;
        this.throttle = throttle;
    }

    public boolean slugAvailable(String raw) {
        String slug = SlugPolicy.validate(raw);
        return !tenants.existsBySlug(slug);
    }

    public Result onboard(Command c, String idempotencyKey, String ip, String userAgent) {
        throttle.check("onboard|" + ip, 10, Duration.ofHours(1));
        String slug = SlugPolicy.validate(c.slug());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> prior = jdbc.sql("SELECT result_id FROM core.idempotency_keys WHERE key = :k AND scope = 'onboarding'")
                    .param("k", idempotencyKey).query(UUID.class).optional();
            if (prior.isPresent()) {
                Tenant t = tenants.findById(prior.get()).orElseThrow();
                return new Result(t, null, null, true);
            }
        }
        auth.validatePassword(c.password());
        String phone = PhoneNormalizer.normalize(c.phone());
        String email = com.platform.shared.PhoneNormalizer.cleanEmail(c.email());
        try {
            Result r = tx.execute(status -> create(c, slug, phone, email, idempotencyKey));
            directory.invalidateAll();   // the new host must resolve immediately, not after a cached "unknown" expires
            TokenResponse tokens = null;
            // Sign the owner in on the new tenant's own host context is the client's job; return platform-level tokens.
            tokens = auth.login(phone, c.password(), ip, userAgent);
            return new Result(r.tenant(), r.domain(), tokens, false);
        } catch (DataIntegrityViolationException e) {
            org.slf4j.LoggerFactory.getLogger(OnboardingService.class).warn("Onboarding constraint violation: {}", e.getMostSpecificCause().getMessage());
            throw BusinessException.conflict("SLUG_TAKEN", "This address is already taken");
        }
    }

    private Result create(Command c, String slug, String phone, String email, String idempotencyKey) {
        if (tenants.existsBySlug(slug)) throw BusinessException.conflict("SLUG_TAKEN", "This address is already taken");

        // Owner account: reuse an existing global identity only if the caller proves ownership with its password.
        User owner = users.findByPhone(phone).orElse(null);
        if (owner == null && email != null) owner = users.findByEmailIgnoreCase(email).orElse(null);
        if (owner != null) {
            if (owner.getPasswordHash() == null || !encoder.matches(c.password(), owner.getPasswordHash()))
                throw BusinessException.conflict("ACCOUNT_EXISTS", "An account with this phone/email already exists; use its password");
        } else {
            owner = new User();
            owner.setPhone(phone);
            owner.setEmail(email);
            owner.setFirstName(c.ownerFirstName());
            owner.setLastName(c.ownerLastName() == null ? "" : c.ownerLastName());
            owner.setPasswordHash(encoder.encode(c.password()));
            owner.setStatus(User.Status.ACTIVE);
            owner = users.saveAndFlush(owner);
        }

        Tenant t = new Tenant();
        t.setSlug(slug);
        t.setName(c.name().trim());
        t.setTenantType(c.type());
        t.setStatus(TenantStatus.TRIAL);
        t.setTrialEndsAt(Instant.now().plus(props.trialDays(), ChronoUnit.DAYS));
        if (c.locale() != null && !c.locale().isBlank()) t.setDefaultLocale(c.locale());
        t = tenants.saveAndFlush(t);

        jdbc.sql("INSERT INTO core.tenant_settings (tenant_id) VALUES (:t)").param("t", t.getId()).update();
        jdbc.sql("INSERT INTO core.branding (tenant_id) VALUES (:t)").param("t", t.getId()).update();

        Membership m = new Membership();
        m.setUserId(owner.getId());
        m.setTenantId(t.getId());
        m = memberships.saveAndFlush(m);
        rbac.assignTenantRole(m.getId(), c.type() == TenantType.STORE ? "STORE_OWNER" : "CLINIC_OWNER");
        if (c.type() == TenantType.CLINIC) rbac.assignTenantRole(m.getId(), "DOCTOR");

        TenantDomain d = domains.createSubdomain(t.getId(), slug);

        events.publishEvent(new com.platform.core.tenant.TenantCreatedEvent(t.getId(), c.type(), owner.getId()));
        for (TenantProvisioner p : provisioners) {
            if (p.supports() == c.type()) p.provision(t, owner.getId(), new ProvisionOptions(c.categories(), c.otherCategory()));
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            jdbc.sql("INSERT INTO core.idempotency_keys (key, scope, result_id) VALUES (:k, 'onboarding', :r)")
                    .param("k", idempotencyKey).param("r", t.getId()).update();
        }
        audit.record(owner.getId(), t.getId(), "TENANT_CREATED", "tenant", t.getId(),
                "{\"type\":\"" + c.type() + "\",\"slug\":\"" + slug + "\"}");
        return new Result(t, d, null, false);
    }
}

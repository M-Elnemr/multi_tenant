package com.platform.core.tenant;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.PlatformProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainService {
    private static final Pattern HOST = Pattern.compile("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String TXT_PREFIX = "_platform-verify.";

    private final TenantDomainRepository domains;
    private final TenantRepository tenants;
    private final PlatformProperties props;
    private final DnsVerifier dns;
    private final AuditService audit;
    private final com.platform.billing.EntitlementService entitlements;
    private final TenantDirectory directory;
    private final String edgeIp;

    public DomainService(TenantDomainRepository domains, TenantRepository tenants, PlatformProperties props,
                         DnsVerifier dns, AuditService audit,
                         com.platform.billing.EntitlementService entitlements, TenantDirectory directory,
                         @org.springframework.beans.factory.annotation.Value("${app.platform.edge-ip:}") String edgeIp) {
        this.edgeIp = edgeIp == null ? "" : edgeIp.trim();
        this.entitlements = entitlements;
        this.directory = directory;
        this.domains = domains;
        this.tenants = tenants;
        this.props = props;
        this.dns = dns;
        this.audit = audit;
    }

    public static String normalizeHost(String raw) {
        if (raw == null) throw BusinessException.badRequest("INVALID_DOMAIN", "Domain is required");
        String h = raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** The other spelling of a custom domain: www.example.com <-> example.com. Proving control of one proves control of the other, so both are served. */
    public static java.util.Optional<String> counterpart(String host) {
        if (host == null || host.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.of(host.startsWith("www.") ? host.substring(4) : "www." + host);
    }

    /** Creates the instant subdomain for a new tenant: verified and primary, no DNS work needed. */
    @Transactional
    public TenantDomain createSubdomain(UUID tenantId, String slug) {
        TenantDomain d = new TenantDomain();
        d.setTenantId(tenantId);
        d.setHost(props.subdomainHost(slug));
        d.setKind(TenantDomain.Kind.SUBDOMAIN);
        d.setPrimary(true);
        d.setVerified(true);
        d.setVerifiedAt(Instant.now());
        d.setSslStatus("WILDCARD");
        return domains.save(d);
    }

    @Transactional
    public TenantDomain addCustomDomain(UUID tenantId, UUID actor, String rawHost) {
        entitlements.requireFeature(tenantId, "custom_domain");
        String host = normalizeHost(rawHost);
        if (!HOST.matcher(host).matches()) throw BusinessException.badRequest("INVALID_DOMAIN", "Invalid domain name");
        if (host.equals(props.rootDomain()) || host.endsWith("." + props.rootDomain()) || props.isPlatformHost(host)) {
            throw BusinessException.badRequest("DOMAIN_RESERVED", "This domain is reserved by the platform");
        }
        if (domains.findByHost(host).isPresent()) throw BusinessException.conflict("DOMAIN_TAKEN", "Domain already in use");
        // the other spelling (www.x / x) belongs to the same owner: another business cannot take it over
        if (counterpart(host).flatMap(domains::findByHost).filter(o -> !o.getTenantId().equals(tenantId)).isPresent()) throw BusinessException.conflict("DOMAIN_TAKEN", "Domain already in use");
        TenantDomain d = new TenantDomain();
        d.setTenantId(tenantId);
        d.setHost(host);
        d.setKind(TenantDomain.Kind.CUSTOM);
        d.setVerificationToken(HexFormat.of().formatHex(randomBytes(20)));
        d.setSslStatus("PENDING");
        d = domains.save(d);
        directory.invalidateAll();
        audit.record(actor, tenantId, "DOMAIN_ADDED", "tenant_domain", d.getId(), "{\"host\":\"" + host + "\"}");
        return d;
    }

    /** Tells the tenant which DNS records to create: one TXT to prove the domain, and the address records for both spellings (root domain and www). */
    public List<DnsInstruction> instructions(TenantDomain d) {
        if (d.getKind() != TenantDomain.Kind.CUSTOM) return List.of();
        List<DnsInstruction> out = new java.util.ArrayList<>();
        out.add(new DnsInstruction("TXT", TXT_PREFIX + d.getHost(), d.getVerificationToken()));
        String host = d.getHost();
        String other = counterpart(host).orElse(null);
        boolean www = host.startsWith("www.");
        // a root domain cannot be a CNAME: point it with an A record when the server address is known
        out.add(www || edgeIp.isEmpty() ? new DnsInstruction("CNAME", host, props.edgeHost()) : new DnsInstruction("A", host, edgeIp));
        if (other != null) out.add(other.startsWith("www.") || edgeIp.isEmpty() ? new DnsInstruction("CNAME", other, props.edgeHost()) : new DnsInstruction("A", other, edgeIp));
        return out;
    }

    /** Live look at the DNS of one domain: which records are right, missing or wrong (the page shows it next to every record). A found TXT also verifies the domain at once. */
    @Transactional
    public java.util.Map<String, Object> check(UUID tenantId, UUID domainId) {
        TenantDomain d = domains.findByIdAndTenantId(domainId, tenantId).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Domain not found"));
        List<java.util.Map<String, Object>> records = new java.util.ArrayList<>();
        if (d.getKind() == TenantDomain.Kind.CUSTOM) {
            List<String> expected = edgeIp.isEmpty() ? dns.addresses(props.edgeHost()) : List.of(edgeIp);
            String registrable = registrable(d.getHost());
            for (DnsInstruction i : instructions(d)) {
                String status; List<String> found; String hint = null;
                String purpose = "TXT".equals(i.type()) ? "VERIFY" : i.name().startsWith("www.") ? "WWW" : "ROOT";
                if ("TXT".equals(i.type())) {
                    found = dns.txtRecords(i.name());
                    if (found.contains(i.value())) status = "OK";
                    else if (!dns.cnames(i.name()).isEmpty()) { status = "WRONG"; found = dns.cnames(i.name()); hint = "CNAME_INSTEAD_OF_TXT"; }
                    else if (!found.isEmpty()) { status = "WRONG"; hint = "WRONG_VALUE"; }
                    else status = "MISSING";
                } else {
                    found = dns.addresses(i.name());
                    if (found.isEmpty()) status = "MISSING";
                    else if (found.stream().anyMatch(expected::contains)) status = "OK";
                    else { status = "WRONG"; hint = "OTHER_ADDRESS"; }
                }
                java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("purpose", purpose); r.put("type", i.type()); r.put("name", i.name()); r.put("shortName", shortName(i.name(), registrable));
                r.put("value", i.value()); r.put("status", status); r.put("found", found); r.put("hint", hint);
                records.add(r);
            }
            if (!d.isVerified() && records.stream().anyMatch(r -> "VERIFY".equals(r.get("purpose")) && "OK".equals(r.get("status")))) tryVerify(d);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("verified", d.isVerified());
        out.put("records", records);
        return out;
    }

    /** example.com for www.example.com / shop.example.com; handles endings like co.uk and com.eg. */
    static String registrable(String host) {
        String[] l = host.split("\\.");
        if (l.length >= 3 && l[l.length - 1].length() == 2 && java.util.Set.of("co", "com", "org", "net", "gov", "edu", "ac").contains(l[l.length - 2]))
            return l[l.length - 3] + "." + l[l.length - 2] + "." + l[l.length - 1];
        return l.length >= 2 ? l[l.length - 2] + "." + l[l.length - 1] : host;
    }

    /** What to type in the provider's "Name / Host" box: @ for the root, the part before the domain otherwise. */
    static String shortName(String full, String registrable) {
        if (full.equals(registrable)) return "@";
        return full.endsWith("." + registrable) ? full.substring(0, full.length() - registrable.length() - 1) : full;
    }

    /** The other spelling that is served together with this domain (shown in the dashboard). */
    public String aliasOf(TenantDomain d) { return d.getKind() == TenantDomain.Kind.CUSTOM ? counterpart(d.getHost()).orElse(null) : null; }

    public record DnsInstruction(String type, String name, String value) {}

    @Transactional
    public TenantDomain verify(UUID tenantId, UUID domainId) {
        TenantDomain d = domains.findByIdAndTenantId(domainId, tenantId)
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Domain not found"));
        if (d.getKind() == TenantDomain.Kind.CUSTOM) entitlements.requireFeature(tenantId, "custom_domain");
        tryVerify(d);
        return d;
    }

    private void tryVerify(TenantDomain d) {
        if (d.isVerified() || d.getKind() != TenantDomain.Kind.CUSTOM) return;
        if (dns.txtRecords(TXT_PREFIX + d.getHost()).contains(d.getVerificationToken())) {
            d.setVerified(true);
            d.setVerifiedAt(Instant.now());
            d.setSslStatus("ISSUING"); // Caddy on-demand TLS issues the cert on first request
            domains.save(d);
            directory.invalidateAll();
            audit.record(null, d.getTenantId(), "DOMAIN_VERIFIED", "tenant_domain", d.getId(), "{\"host\":\"" + d.getHost() + "\"}");
        }
    }

    @Transactional
    public TenantDomain makePrimary(UUID tenantId, UUID domainId) {
        TenantDomain target = domains.findByIdAndTenantId(domainId, tenantId)
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Domain not found"));
        if (target.getKind() == TenantDomain.Kind.CUSTOM) entitlements.requireFeature(tenantId, "custom_domain");
        if (!target.isVerified()) throw BusinessException.badRequest("DOMAIN_NOT_VERIFIED", "Verify the domain first");
        for (TenantDomain other : domains.findByTenantIdOrderByCreatedAtAsc(tenantId)) {
            if (other.isPrimary() && !other.getId().equals(target.getId())) {
                other.setPrimary(false);
                domains.saveAndFlush(other);
            }
        }
        target.setPrimary(true);
        directory.invalidateAll();
        return domains.save(target);
    }

    @Transactional
    public void remove(UUID tenantId, UUID actor, UUID domainId) {
        TenantDomain d = domains.findByIdAndTenantId(domainId, tenantId)
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Domain not found"));
        if (d.isPrimary()) throw BusinessException.badRequest("DOMAIN_IS_PRIMARY", "Make another domain primary first");
        if (d.getKind() == TenantDomain.Kind.SUBDOMAIN) throw BusinessException.badRequest("DOMAIN_REQUIRED", "The platform subdomain cannot be removed");
        domains.delete(d);
        directory.invalidateAll();
        audit.record(actor, tenantId, "DOMAIN_REMOVED", "tenant_domain", d.getId(), "{\"host\":\"" + d.getHost() + "\"}");
    }

    public List<TenantDomain> list(UUID tenantId) {
        return domains.findByTenantIdOrderByCreatedAtAsc(tenantId);
    }

    /** Used by the reverse proxy's on-demand TLS "ask" hook: only verified hosts of live tenants get certificates. */
    public boolean isAllowedForTls(String rawHost) {
        String host = normalizeHost(rawHost);
        if (props.isPlatformHost(host) || host.equals(props.edgeHost())) return true;
        return domains.findByHost(host).filter(TenantDomain::isVerified)
                .or(() -> counterpart(host).flatMap(domains::findByHost).filter(o -> o.isVerified() && o.getKind() == TenantDomain.Kind.CUSTOM))
                .flatMap(d -> tenants.findById(d.getTenantId()))
                .map(t -> t.getStatus() != TenantStatus.ARCHIVED && t.getStatus() != TenantStatus.CANCELLED)
                .orElse(false);   // (a custom domain whose plan lost the feature stops resolving, see TenantDirectory)
    }

    /** Background job: poll DNS for custom domains still waiting for verification. */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT30S")
    @Transactional
    public void verifyPending() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        domains.findByKindAndVerifiedFalse(TenantDomain.Kind.CUSTOM).stream()
                .filter(d -> d.getCreatedAt().isAfter(cutoff))
                .forEach(this::tryVerify);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}

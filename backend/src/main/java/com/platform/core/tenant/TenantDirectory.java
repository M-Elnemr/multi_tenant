package com.platform.core.tenant;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.platform.shared.TenantContext;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Host -> tenant lookups happen on every request, so they are cached in-process (a few thousand entries, microseconds per hit) instead of
 * hitting PostgreSQL twice per call. Known tenants are cached 30 s; unknown hosts only 5 s but they ARE cached, so a flood of random
 * Host headers cannot turn into a flood of database queries. Anything that changes a domain or a tenant's status calls {@link #invalidateAll()}
 * on its own instance; other instances converge within the TTL.
 */
@Component
public class TenantDirectory {
    private static final Duration KNOWN = Duration.ofSeconds(30);
    private static final Duration UNKNOWN = Duration.ofSeconds(5);

    private final LoadingCache<String, Optional<TenantContext.Current>> cache;

    public TenantDirectory(TenantDomainRepository domains, TenantRepository tenants) {
        this.cache = Caffeine.newBuilder().maximumSize(50_000).expireAfter(new Expiry<String, Optional<TenantContext.Current>>() {
            @Override public long expireAfterCreate(String k, Optional<TenantContext.Current> v, long now) { return (v.isPresent() ? KNOWN : UNKNOWN).toNanos(); }
            @Override public long expireAfterUpdate(String k, Optional<TenantContext.Current> v, long now, long cur) { return cur; }
            @Override public long expireAfterRead(String k, Optional<TenantContext.Current> v, long now, long cur) { return cur; }
        }).build(host -> domains.findByHost(host).filter(TenantDomain::isVerified).flatMap(d -> tenants.findById(d.getTenantId()))
                .map(t -> new TenantContext.Current(t.getId(), t.getSlug(), t.getTenantType().name(), t.getStatus().name(), host)));
    }

    public Optional<TenantContext.Current> resolve(String host) { return cache.get(host); }

    public void invalidateAll() { cache.invalidateAll(); }
}

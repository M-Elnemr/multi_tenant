package com.platform.core.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDomainRepository extends JpaRepository<TenantDomain, UUID> {
    Optional<TenantDomain> findByHost(String host);
    List<TenantDomain> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
    List<TenantDomain> findByKindAndVerifiedFalse(TenantDomain.Kind kind);
    Optional<TenantDomain> findByIdAndTenantId(UUID id, UUID tenantId);
}

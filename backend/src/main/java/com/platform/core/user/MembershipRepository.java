package com.platform.core.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    Optional<Membership> findByUserIdAndTenantId(UUID userId, UUID tenantId);
    List<Membership> findByUserId(UUID userId);
}

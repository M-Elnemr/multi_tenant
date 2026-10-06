package com.platform.core.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_tenant_memberships", schema = "core")
@Getter @Setter
public class Membership {
    public enum Status { ACTIVE, SUSPENDED, REMOVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private UUID tenantId;
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}

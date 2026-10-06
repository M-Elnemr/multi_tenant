package com.platform.core.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenant_domains", schema = "core")
@Getter @Setter
public class TenantDomain {
    public enum Kind { SUBDOMAIN, CUSTOM }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID tenantId;
    private String host;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Column(name = "is_primary")
    private boolean primary;
    @Column(name = "is_verified")
    private boolean verified;
    private String verificationToken;
    private Instant verifiedAt;
    private String sslStatus;
    private Instant createdAt = Instant.now();
}

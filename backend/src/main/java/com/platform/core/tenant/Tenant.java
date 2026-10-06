package com.platform.core.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenants", schema = "core")
@Getter @Setter
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String slug;
    private String name;
    private String legalName;
    @Enumerated(EnumType.STRING) @Column(name = "tenant_type")
    private TenantType tenantType;
    @Enumerated(EnumType.STRING)
    private TenantStatus status;
    private String defaultLocale = "ar";
    private String defaultCurrency = "EGP";
    private String timezone = "Africa/Cairo";
    private Instant trialEndsAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}

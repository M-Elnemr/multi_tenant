package com.platform.core.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users", schema = "core")
@Getter @Setter
public class User {
    public enum Status { INVITED, ACTIVE, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String email;
    private String phone;
    private String passwordHash;
    private String firstName;
    private String lastName = "";
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant lastLoginAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}

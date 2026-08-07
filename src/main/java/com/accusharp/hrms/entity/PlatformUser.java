package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.PlatformRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A platform-level account: someone who administers the platform itself
 * (company onboarding, cross-company operations), not an {@link Employee} of
 * any one company. Kept as a separate principal type rather than a nullable
 * "companyless" Employee, since platform staff carry none of the payroll /
 * attendance fields that make Employee what it is.
 */
@Entity
@Table(name = "platform_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_user_username", columnNames = "username"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformRole role;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "account_locked", nullable = false)
    @Builder.Default
    private boolean accountLocked = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

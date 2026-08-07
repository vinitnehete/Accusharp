package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.PrincipalType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Server-side record of an issued refresh token, so logout and reuse
 * detection are real revocation rather than "wait for it to expire". Only a
 * SHA-256 hash of the token is stored - the raw value exists only in the
 * client's hands and the instant it was issued.
 */
@Entity
@Table(name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_refresh_token_principal", columnList = "principal_type,principal_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 20)
    private PrincipalType principalType;

    /** Employee.userId or PlatformUser.id (as a string), depending on principalType. */
    @Column(name = "principal_id", nullable = false, length = 50)
    private String principalId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

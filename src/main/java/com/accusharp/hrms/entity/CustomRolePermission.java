package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@link Permission} granted to one {@link CustomRole}. Kept as its own
 * table rather than reusing {@code RolePermission} - that table backs
 * {@code PermissionRegistry}'s in-memory cache, loaded once at startup and
 * never invalidated (see its Javadoc); custom-role grants are edited at
 * runtime, so they're resolved fresh from this table on every check instead
 * (see {@code AuthorizationService}'s fallback), keeping the fixed-at-startup
 * and edited-at-runtime grant systems from being conflated into one cache
 * with two different invalidation stories.
 */
@Entity
@Table(name = "custom_role_permission", uniqueConstraints = @UniqueConstraint(
        name = "uk_custom_role_permission", columnNames = {"custom_role_id", "permission_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomRolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custom_role_id")
    private CustomRole customRole;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id")
    private Permission permission;
}

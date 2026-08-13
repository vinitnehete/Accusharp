package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RoleScope;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Grants one {@link Permission} to one role name within one {@link RoleScope}.
 *
 * <p>{@code roleName} is a plain string (not a foreign key to an enum) so
 * that a future "custom company roles" feature can add rows here without a
 * schema change - today it is always one of {@link com.accusharp.hrms.enums.Role}
 * or {@link com.accusharp.hrms.enums.PlatformRole}'s names, seeded by
 * {@link com.accusharp.hrms.config.PermissionSeeder}. Employees and platform
 * users each carry exactly one role today (a plain enum column), so there is
 * deliberately no separate "UserRole" join table yet - that arrives if/when
 * multiple roles per user are needed.
 */
@Entity
@Table(name = "role_permission",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_permission",
                columnNames = {"role_scope", "role_name", "permission_id"}),
        indexes = @Index(name = "idx_role_permission_lookup", columnList = "role_scope,role_name"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false, length = 20)
    private RoleScope roleScope;

    @Column(name = "role_name", nullable = false, length = 30)
    private String roleName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id")
    private Permission permission;
}

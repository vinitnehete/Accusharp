package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single grantable capability, e.g. {@code EMPLOYEE_CREATE}. Referenced by
 * code (not id) from {@code @PreAuthorize} expressions via
 * {@link com.accusharp.hrms.security.AuthorizationService}, so the code is
 * the stable identifier - never rename one in place, add a new one and
 * migrate {@link RolePermission} rows instead.
 */
@Entity
@Table(name = "permission", uniqueConstraints = @UniqueConstraint(name = "uk_permission_code", columnNames = "code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;
}

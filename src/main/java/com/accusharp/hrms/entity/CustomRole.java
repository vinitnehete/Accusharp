package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A company-defined role: a named bundle of {@link Permission}s (via {@link
 * CustomRolePermission}) that can be assigned to any number of that
 * company's employees (via {@link EmployeeCustomRole}), on top of - never
 * instead of - their fixed {@link com.accusharp.hrms.enums.Role}.
 *
 * <p>Deliberately additive rather than a replacement for {@code
 * Employee.role}: every existing business rule that inspects the enum
 * directly (self-escalation guard, supervisor-team checks, self-service
 * scoping) keeps working unchanged. A custom role only ever <em>adds</em>
 * permissions on top of what the base role already grants - see {@code
 * AuthorizationService}'s fallback check and {@code
 * CustomRoleService}'s platform-only-code guard (a company can never grant
 * itself a platform-scoped permission through this mechanism).
 *
 * <p>Always company-scoped - there is no "global" custom role the way
 * {@code Shift}/{@code Department} have a shared catalog; a custom role is
 * inherently a per-company thing to define.
 */
@Entity
@Table(name = "custom_role", uniqueConstraints = @UniqueConstraint(name = "uk_custom_role_company_name",
        columnNames = {"company_id", "name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 300)
    private String description;

    /**
     * {@code @Builder.Default}: without it, Lombok's builder bypasses this
     * field initializer entirely (a known builder/field-initializer gotcha),
     * leaving a freshly built-and-saved role's collection {@code null}
     * rather than empty until the entity is reloaded from the database.
     */
    @JsonIgnore
    @Builder.Default
    @OneToMany(mappedBy = "customRole", fetch = FetchType.LAZY)
    private List<CustomRolePermission> grantedPermissions = new ArrayList<>();
}

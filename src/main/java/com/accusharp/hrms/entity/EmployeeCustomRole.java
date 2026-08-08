package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One {@link CustomRole} assigned to one {@link Employee}. An employee may hold any number of custom roles at once, on top of their fixed {@code Role}. */
@Entity
@Table(name = "employee_custom_role", uniqueConstraints = @UniqueConstraint(
        name = "uk_employee_custom_role", columnNames = {"employee_id", "custom_role_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeCustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custom_role_id")
    private CustomRole customRole;
}

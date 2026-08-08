package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per company, plus rows with {@code company = null} - a shared
 * catalog every company can see and use (the seeded demo departments today)
 * but only a platform caller may edit, never a company ADMIN/HR. See
 * {@code SalaryRule}'s Javadoc for the precedent; unlike that class this one
 * is a list rather than a singleton settings object, so "global" here means
 * "shared reference rows," not "the default until customized."
 */
@Entity
@Table(name = "department", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "department_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a shared row every company can see. See class Javadoc. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "department_code", nullable = false, length = 30)
    private String departmentCode;

    @Column(name = "department_name", nullable = false)
    private String departmentName;

    @Column(length = 500)
    private String description;
}

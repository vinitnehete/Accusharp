package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Employee master. Holds the salary <em>structure</em>, never a specific
 * month's pay - that lives in {@link Payroll}.
 *
 * <p>{@code userId} is the business key used across attendance, leave and
 * payroll; {@code id} never leaves this table.
 */
@Entity
@Table(name = "employee",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_employee_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_employee_code", columnNames = "employee_code")
        },
        indexes = @Index(name = "idx_employee_supervisor", columnList = "supervisor_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key - also the biometric device user id. */
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "employee_code", nullable = false, length = 50)
    private String employeeCode;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    private Designation designation;

    /** Self-reference: one employee reports to at most one supervisor. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private Employee supervisor;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmployeeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 20)
    private RecordStatus recordStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    private String email;

    @Column(length = 20)
    private String phone;

    // ---- salary structure -------------------------------------------------
    // grossSalary, pfBasic, medicalAllowance and otherAllowance are entered;
    // the rest are derived from SalaryRule on every create/update.

    @Column(name = "gross_salary", precision = 15, scale = 2)
    private BigDecimal grossSalary;

    @Column(name = "pf_basic", precision = 15, scale = 2)
    private BigDecimal pfBasic;

    @Column(name = "basic_da", precision = 15, scale = 2)
    private BigDecimal basicDA;

    @Column(precision = 15, scale = 2)
    private BigDecimal hra;

    @Column(name = "conveyance_allowance", precision = 15, scale = 2)
    private BigDecimal conveyanceAllowance;

    @Column(name = "education_allowance", precision = 15, scale = 2)
    private BigDecimal educationAllowance;

    @Column(name = "medical_allowance", precision = 15, scale = 2)
    private BigDecimal medicalAllowance;

    @Column(name = "other_allowance", precision = 15, scale = 2)
    private BigDecimal otherAllowance;

    /** Sum of every earning component above. */
    @Column(name = "gross_salary_wage", precision = 15, scale = 2)
    private BigDecimal grossSalaryWage;

    @Column(name = "overtime_eligible", nullable = false)
    private boolean overtimeEligible;
}

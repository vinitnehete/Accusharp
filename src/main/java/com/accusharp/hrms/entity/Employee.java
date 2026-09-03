package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
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
                // Global, not per-company, deliberately: login (AuthService#dispatchLogin)
                // and the biometric device feed both resolve an employee by userId alone,
                // with no company selector in either flow - two companies sharing a userId
                // would make login ambiguous. See docs/migrations/2026-08-08-per-company-masters.sql
                // for the equivalent per-company move made for Department/Designation/Shift,
                // which don't have that constraint.
                @UniqueConstraint(name = "uk_employee_user_id", columnNames = "user_id"),
                // Per-company (unlike userId above): employeeCode is a display/reporting
                // code, never looked up across companies, so two companies numbering their
                // own staff "EMP001" independently is the expected case, not a collision.
                @UniqueConstraint(name = "uk_employee_company_code", columnNames = {"company_id", "employee_code"})
        },
        indexes = {
                @Index(name = "idx_employee_supervisor", columnList = "supervisor_id"),
                // company_id/department_id/designation_id/category_id are all hit by
                // frequently-used repository finders (findByCompanyId, findByDepartmentId,
                // findByCategoryId, and the delete-guard checks in DepartmentService/
                // CategoryService) - unindexed, these degrade to full table scans as
                // headcount grows. Flagged in the audit's database review.
                @Index(name = "idx_employee_company", columnList = "company_id"),
                @Index(name = "idx_employee_department", columnList = "department_id"),
                @Index(name = "idx_employee_designation", columnList = "designation_id"),
                @Index(name = "idx_employee_category", columnList = "category_id")
        })
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

    /** Employee grade/category (Worker, Supervisor, Manager, Director, ...) - optional, company-defined. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Self-reference: one employee reports to at most one supervisor. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private Employee supervisor;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    /**
     * Last working day, set when the employee is deactivated. Null while
     * active. Payroll uses this (and {@link #joiningDate}) to bound how many
     * days of a period this employee was actually employed for - see
     * {@code PayrollService#employedDaysInPeriod}.
     */
    @Column(name = "relieving_date")
    private LocalDate relievingDate;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Optional. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmployeeStatus status;

    /**
     * The configurable employment type, and the payroll behaviour that comes
     * with it - see {@link EmploymentType}.
     *
     * <p><b>Nullable, and {@link #status} above stays.</b> The two coexist on
     * purpose: null here means payroll falls back to the legacy
     * {@link EmployeeStatus} semantics, which is exactly what every existing
     * row does and exactly what it did before this column existed. Adopting
     * configurable types is therefore opt-in per employee rather than a
     * migration a company must finish before its next payroll run, and rolling
     * back is clearing one column.
     *
     * <p>{@code status} remains the field the CSV importer, the roster default
     * and every report read, so nothing outside {@code PayBehaviourResolver}
     * has to know this column exists yet.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employment_type_id")
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 20)
    private RecordStatus recordStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    private String email;

    @Column(length = 20)
    private String phone;

    // ---- statutory & bank details - all optional -----------------------
    // Encrypted at rest (AES-256-GCM) - see EncryptedStringConverter. These
    // four are the only employee fields worth anything to someone who gets a
    // copy of the database, and none of them is ever queried or filtered
    // (only set and mapped onto a response), which is what makes encrypting
    // them free of functional consequence.
    //
    // The lengths below are CIPHERTEXT widths, not value widths: GCM plus
    // base64 expands a 30-character account number to roughly 90.

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "uan_no", length = 255)
    private String uanNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "esic_ip_no", length = 255)
    private String esicIpNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bank_account_no", length = 255)
    private String bankAccountNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bank_ifsc_no", length = 255)
    private String bankIfscNo;

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

    /**
     * When true, basicDA/hra/conveyanceAllowance/educationAllowance were set
     * by hand (see {@code EmployeeService#updateSalaryStructure}) and are no
     * longer derived from {@link SalaryRule} on save - until
     * {@code EmployeeService#regenerateSalaryStructure} clears it.
     */
    @Column(name = "salary_structure_overridden", nullable = false)
    @Builder.Default
    private boolean salaryStructureOverridden = false;

    @Column(name = "overtime_eligible", nullable = false)
    private boolean overtimeEligible;

    // ---- authentication -----------------------------------------------------
    // userId doubles as the login username. Never serialized to any response DTO.

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "account_enabled", nullable = false)
    @Builder.Default
    private boolean accountEnabled = true;

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

    /**
     * Set whenever HR hands this employee a system-generated temporary
     * password (create, reset-password) - never by anything else, so
     * existing accounts are unaffected. Cleared once the employee actually
     * sets their own password via {@code POST /api/auth/change-password}.
     * Surfaced on {@code TokenResponse} so the frontend can force the
     * change-password screen before letting a temporary password stay live
     * indefinitely - the gap the audit's authentication review flagged.
     */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;
}

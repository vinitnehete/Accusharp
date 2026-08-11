package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.PayrollStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The monthly payroll record - a full snapshot of everything the calculation
 * consumed (employee salary structure, salary rules, attendance, leave, LOP,
 * overtime and manual deductions), so later edits to any of those never
 * retroactively change an already-generated month.
 *
 * <p>Regenerating a period does not overwrite: the previous row is marked
 * {@link PayrollStatus#SUPERSEDED} and a new {@code revision} is written, so
 * payroll history stays immutable and auditable.
 */
@Entity
@Table(name = "payroll",
        uniqueConstraints = @UniqueConstraint(name = "uk_payroll_period_revision",
                columnNames = {"employee_id", "`month`", "`year`", "revision"}),
        indexes = @Index(name = "idx_payroll_period", columnList = "`month`,`year`,status"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Employee.userId business key. */
    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    /** Quoted: "month" and "year" are reserved words on some databases. */
    @Column(name = "`month`", nullable = false)
    private Integer month;

    @Column(name = "`year`", nullable = false)
    private Integer year;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayrollStatus status;

    // ---- employee snapshot -------------------------------------------------
    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "department_name")
    private String departmentName;

    @Column(name = "designation_name")
    private String designationName;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", length = 20)
    private EmployeeStatus employmentStatus;

    @Column(name = "gross_salary", precision = 15, scale = 2)
    private BigDecimal grossSalary;

    @Column(name = "gross_salary_wage", precision = 15, scale = 2)
    private BigDecimal grossSalaryWage;

    @Column(name = "pf_basic", precision = 15, scale = 2)
    private BigDecimal pfBasic;

    // ---- attendance snapshot ----------------------------------------------
    @Column(name = "days_in_month", nullable = false)
    private Integer daysInMonth;

    @Column(name = "working_days", nullable = false)
    private Integer workingDays;

    @Column(name = "present_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal presentDays;

    @Column(name = "paid_leave_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal paidLeaveDays;

    @Column(name = "lop_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal lopDays;

    /** Days actually paid: workingDays - lopDays (clamped at zero). */
    @Column(name = "payable_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal payableDays;

    @Column(name = "total_hours", precision = 8, scale = 2)
    private BigDecimal totalHours;

    @Column(name = "overtime_hours", precision = 8, scale = 2)
    private BigDecimal overtimeHours;

    @Column(name = "per_day", precision = 15, scale = 2)
    private BigDecimal perDay;

    @Column(name = "per_hour", precision = 15, scale = 2)
    private BigDecimal perHour;

    // ---- earnings ----------------------------------------------------------
    @Column(name = "earn_basic_da", precision = 15, scale = 2)
    private BigDecimal earnBasicDA;

    @Column(name = "earn_hra", precision = 15, scale = 2)
    private BigDecimal earnHra;

    @Column(name = "earn_conveyance", precision = 15, scale = 2)
    private BigDecimal earnConveyance;

    @Column(name = "earn_education", precision = 15, scale = 2)
    private BigDecimal earnEducation;

    @Column(name = "earn_medical", precision = 15, scale = 2)
    private BigDecimal earnMedical;

    @Column(name = "earn_other", precision = 15, scale = 2)
    private BigDecimal earnOther;

    @Column(precision = 15, scale = 2)
    private BigDecimal bonus;

    @Column(precision = 15, scale = 2)
    private BigDecimal incentive;

    @Column(name = "ot_allowance", precision = 15, scale = 2)
    private BigDecimal otAllowance;

    @Column(name = "earn_gross_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal earnGrossSalary;

    /** Sum of every earning including bonus, incentive and overtime. */
    @Column(name = "total_earnings", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalEarnings;

    // ---- deductions --------------------------------------------------------
    /** Full-month PF on pfBasic - informational, not deducted. */
    @Column(precision = 15, scale = 2)
    private BigDecimal pf;

    /** pfBasic prorated by payable days - the base the PF deduction uses. */
    @Column(name = "earn_pf", precision = 15, scale = 2)
    private BigDecimal earnPf;

    @Column(name = "pf_deduction", precision = 15, scale = 2)
    private BigDecimal pfDeduction;

    @Column(precision = 15, scale = 2)
    private BigDecimal esic;

    @Column(name = "professional_tax", precision = 15, scale = 2)
    private BigDecimal professionalTax;

    /** Labour Welfare Fund - non-zero only in the June and December cycle. */
    @Column(precision = 15, scale = 2)
    private BigDecimal mlwf;

    @Column(precision = 15, scale = 2)
    private BigDecimal tds;

    @Column(name = "advance_deduction", precision = 15, scale = 2)
    private BigDecimal advanceDeduction;

    @Column(name = "loan_deduction", precision = 15, scale = 2)
    private BigDecimal loanDeduction;

    @Column(precision = 15, scale = 2)
    private BigDecimal canteen;

    /** Value of the unpaid days, shown on the slip for transparency. */
    @Column(name = "lop_deduction", precision = 15, scale = 2)
    private BigDecimal lopDeduction;

    @Column(name = "total_deduction", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDeduction;

    @Column(name = "net_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal netSalary;

    // ---- salary rule snapshot ---------------------------------------------
    @Column(name = "rule_basic_da_percent", precision = 6, scale = 2)
    private BigDecimal ruleBasicDaPercent;

    @Column(name = "rule_pf_percent", precision = 6, scale = 2)
    private BigDecimal rulePfPercent;

    @Column(name = "rule_esic_percent", precision = 6, scale = 2)
    private BigDecimal ruleEsicPercent;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by", length = 50)
    private String generatedBy;
}

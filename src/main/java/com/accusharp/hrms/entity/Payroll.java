package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.PayBasis;
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

    /**
     * The employment type's code, and the two behaviours the reports need,
     * snapshotted the way {@code rulePfPercent} and the other {@code rule*}
     * columns already are.
     *
     * <p>Snapshotted rather than re-derived because an employment type is now an
     * editable row. Without these, a company that changes a type's pay basis
     * would silently change how every historical payslip is <em>formatted and
     * reconciled</em> - the day-wise register lays a period out completely
     * differently from a calendar-day one - even though the money on those
     * payslips never moved. ARCHITECTURE.md's "immutable payroll history"
     * guarantee is only true if the shape of the history is snapshotted too.
     *
     * <p>Null on every payroll generated before employment types existed. The
     * reports fall back to {@link #employmentStatus} in that case, which is
     * exactly what they read before, so historical rows keep rendering as they
     * always did.
     */
    @Column(name = "employment_type_code", length = 30)
    private String employmentTypeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_basis", length = 30)
    private PayBasis payBasis;

    /** The fixed monthly base this period was prorated against, for a per-attended-day type. */
    @Column(name = "payable_days_cap")
    private Integer payableDaysCap;

    /**
     * Whether this period was paid per attended day - the one question the
     * registers and the audit report ask.
     *
     * <p>Reads the snapshotted {@link #payBasis} where there is one, and falls
     * back to the legacy enum for payrolls generated before employment types
     * existed. Callers must use this rather than
     * {@code employmentStatus.isPaidPerAttendedDay()}: the two disagree for any
     * employee whose configured type differs from their legacy status, and the
     * snapshot is the one that says how this period was actually computed.
     */
    public boolean wasPaidPerAttendedDay() {
        if (payBasis != null) {
            return payBasis.isPaidPerAttendedDay();
        }
        return employmentStatus != null && employmentStatus.isPaidPerAttendedDay();
    }

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

    /** DAY_WISE's payable-day base and everyone's overtime-hour base at generation time. */
    @Column(name = "rule_day_wise_days_in_month")
    private Integer ruleDayWiseDaysInMonth;

    @Column(name = "rule_standard_hours_per_day", precision = 4, scale = 1)
    private BigDecimal ruleStandardHoursPerDay;

    @Column(name = "rule_overtime_rate_multiplier", precision = 4, scale = 2)
    private BigDecimal ruleOvertimeRateMultiplier;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by", length = 50)
    private String generatedBy;

    /**
     * Optimistic lock. Two concurrent {@code regenerate()} calls both reading
     * the same GENERATED row and both marking it SUPERSEDED - the second
     * writer here gets a clean version-conflict failure instead of silently
     * clobbering the first writer's status change before either has
     * attempted the new revision's insert (which the {@code
     * uk_payroll_period_revision} unique constraint guards separately).
     */
    @Version
    private Long version;
}

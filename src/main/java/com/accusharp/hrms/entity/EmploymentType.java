package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An employment type, and the payroll behaviour that goes with it - as a row a
 * company can edit rather than a constant in a Java enum.
 *
 * <p>One row per company plus rows with {@code company = null} - the shared,
 * read-only-to-companies catalog, the same shape {@link Shift}, {@link Category}
 * and {@code Department} have used since SECURITY.md Phase 6.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code EmployeeStatus} is a fixed enum of four, and
 * {@code EmployeeStatus.isPaidPerAttendedDay()} drove seven separate branches in
 * {@code PayrollService.build}. A company could change the numbers those
 * branches used - {@code dayWiseDaysInMonth}, {@code standardHoursPerDay}, both
 * on {@link SalaryRule} - but never the behaviour, and could not add a fifth
 * type at all. Two clients with different definitions of "contract" had no way
 * to express it.
 *
 * <h2>Backward compatibility</h2>
 *
 * <p>{@code Employee.status} stays exactly where it is and keeps working.
 * {@code Employee.employmentType} is a <b>nullable</b> addition, and
 * {@code PayBehaviourResolver} falls back to the legacy enum semantics whenever
 * it is unset - so an existing database with no rows in this table pays
 * everybody precisely as it did before, and adopting the table is opt-in per
 * employee. See that class for the fallback table.
 *
 * <p>{@code payableDaysCap} is deliberately nullable even for a per-attended-day
 * type: null means "use the company's {@code salaryRule.dayWiseDaysInMonth}",
 * which is where that number lives today. Setting it here overrides that for
 * this type alone, which is what lets two day-wise populations differ - the
 * thing {@link SalaryRule}'s single company-wide figure cannot express.
 */
@Entity
@Table(name = "employment_type",
        uniqueConstraints = @UniqueConstraint(name = "uk_employment_type_company_code",
                columnNames = {"company_id", "type_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmploymentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a shared row every company can see. See class Javadoc. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "type_code", nullable = false, length = 30)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 60)
    private String typeName;

    // ---- how pay is derived ------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_basis", nullable = false, length = 30)
    private PayBasis payBasis;

    /**
     * The fixed monthly base for a per-attended-day type. Null falls back to the
     * company's {@code salaryRule.dayWiseDaysInMonth} (26 by default), which is
     * where this number lives today.
     */
    @Column(name = "payable_days_cap")
    private Integer payableDaysCap;

    /**
     * Whether attendance shortfalls become loss of pay. False for a
     * per-attended-day type, where being absent simply means not being paid for
     * that day rather than owing one.
     */
    @Column(name = "lop_applies", nullable = false)
    @Builder.Default
    private boolean lopApplies = true;

    /** Whether approved paid leave earns a share of the fixed salary structure on top of attended days. */
    @Column(name = "paid_leave_adds_payable_days", nullable = false)
    @Builder.Default
    private boolean paidLeaveAddsPayableDays = true;

    // ---- how overtime is derived -------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "overtime_basis", nullable = false, length = 30)
    private OvertimeBasis overtimeBasis;

    /** Whether approved paid leave adds its own hours to overtime, uncapped and additive. */
    @Column(name = "paid_leave_earns_overtime", nullable = false)
    @Builder.Default
    private boolean paidLeaveEarnsOvertime = false;

    // ---- the rest ----------------------------------------------------------

    /** Whether a mid-period salary revision splits the gross-derived earnings across both rates. */
    @Column(name = "segmented_revision_earnings", nullable = false)
    @Builder.Default
    private boolean segmentedRevisionEarnings = true;

    /**
     * Whether {@code DefaultRosterService} auto-rosters this type onto the
     * {@code GENERAL} shift. Only {@code PERMANENT} does today.
     */
    @Column(name = "auto_roster_default_shift", nullable = false)
    @Builder.Default
    private boolean autoRosterDefaultShift = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}

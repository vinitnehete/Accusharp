package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response shapes for the monthly payroll audit report - the line-by-line
 * view HR reconciles a payroll run against before releasing it.
 *
 * <p>Every money and day figure here is read straight off the immutable
 * {@link com.accusharp.hrms.entity.Payroll} snapshot; nothing is recomputed,
 * for the same reason {@code ReportService} never recomputes - an audit that
 * disagreed with the salary slip would be worse than no audit at all. The
 * only exceptions are explicitly labelled as such: {@code regularHours}
 * (totalHours minus overtimeHours) and the day-wise table's {@code dayWage},
 * both arithmetic over already-stored figures rather than a second opinion
 * on them.
 */
public final class PayrollAuditDtos {

    private PayrollAuditDtos() {
    }

    /**
     * One employee's audit line for one month.
     *
     * <p>The {@code fixedXxx} component split is the <em>current</em>
     * employee master structure, because {@code Payroll} snapshots only the
     * gross figures ({@code grossSalary}, {@code grossSalaryWage},
     * {@code pfBasic}) and not the per-component breakup. When the master has
     * moved since the run, {@code salaryStructureDrifted} is set - same
     * signal {@code PayrollDebugRow} raises for the same reason. The
     * {@code earnXxx} lines are always the snapshot and are never affected.
     */
    public record AuditRow(
            // ---- identity ----
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            String categoryName,
            LocalDate joiningDate,
            LocalDate relievingDate,
            EmployeeStatus employmentStatus,

            // ---- period & attendance ----
            int daysInMonth,
            int workingDays,
            BigDecimal presentDays,
            BigDecimal paidLeaveDays,
            BigDecimal lopDays,
            BigDecimal payableDays,
            /** The denominator every earning was prorated over - 26 (or the configured figure) for DAY_WISE, calendar days for everyone else. */
            BigDecimal prorationBase,

            // ---- fixed wage components (CTC breakup) ----
            BigDecimal fixedGrossSalary,
            BigDecimal fixedBasicDA,
            BigDecimal fixedHra,
            BigDecimal fixedConveyance,
            BigDecimal fixedEducation,
            BigDecimal fixedMedical,
            BigDecimal fixedOther,
            BigDecimal fixedGrossWage,
            BigDecimal fixedPfBasic,
            boolean salaryStructureDrifted,

            // ---- earned wages ----
            BigDecimal earnBasicDA,
            BigDecimal earnHra,
            BigDecimal earnConveyance,
            BigDecimal earnEducation,
            BigDecimal earnMedical,
            BigDecimal earnOther,
            BigDecimal earnGrossSalary,
            BigDecimal bonus,
            BigDecimal incentive,
            BigDecimal totalEarnings,

            // ---- per-day / per-hour rate ----
            BigDecimal perDay,
            BigDecimal perHour,

            // ---- overtime ----
            boolean overtimeEligible,
            BigDecimal standardHoursPerDay,
            BigDecimal overtimeRateMultiplier,
            BigDecimal overtimeHours,
            BigDecimal otAllowance,

            // ---- hours worked ----
            BigDecimal totalHours,
            BigDecimal regularHours,

            // ---- deductions ----
            BigDecimal pfDeduction,
            BigDecimal esic,
            BigDecimal professionalTax,
            BigDecimal mlwf,
            BigDecimal tds,
            BigDecimal advanceDeduction,
            BigDecimal loanDeduction,
            BigDecimal canteen,
            /** Value of the unpaid days - shown for transparency, already reflected in the prorated earnings rather than deducted again. */
            BigDecimal lopDeduction,
            BigDecimal totalDeduction,

            BigDecimal netSalary,
            int revision,
            Instant generatedAt
    ) {
    }

    /** One calendar day of the month, with what it was worth. */
    public record DayWiseRow(
            LocalDate date,
            String dayOfWeek,
            String shiftCode,
            /** False when attendance was never generated for this date - a mid-month joiner's earlier days, typically. */
            boolean recorded,
            AttendanceStatus status,
            LeaveType leaveType,
            boolean weekOff,
            boolean holiday,
            boolean workingDay,
            LocalDateTime firstIn,
            LocalDateTime lastOut,
            BigDecimal workingHours,
            BigDecimal overtimeHours,
            int lateMinutes,
            int earlyExitMinutes,
            boolean invalidPunch,
            /** How much of this day was paid: 1.0, 0.5 or 0. */
            BigDecimal paidFraction,
            /** How much of this day was loss of pay. Always zero for DAY_WISE, which has no LOP concept. */
            BigDecimal lopFraction,
            BigDecimal dayWage,
            BigDecimal overtimeAmount,
            BigDecimal dayTotal
    ) {
    }

    /**
     * The day-wise drill-down for one employee for one month.
     *
     * <p>Two different money figures sit on this record, and they are
     * <em>not</em> two attempts at the same number:
     *
     * <ul>
     *   <li>{@code dayWiseWageTotal} is {@code perDay} summed over the paid
     *       fractions below. {@code perDay} is the rate payroll stores -
     *       {@code grossSalary / prorationBase} - and is what the overtime
     *       per-hour rate derives from.</li>
     *   <li>{@code earnedGrossSalary} is what was actually earned, and
     *       prorates each component of {@code grossSalaryWage} instead.</li>
     * </ul>
     *
     * <p>{@code grossSalary} and {@code grossSalaryWage} are different
     * amounts by design - the wage is the sum of the structured components,
     * the gross is the figure the structure is derived from - so these two
     * totals differ by that same ratio and always will. Only
     * {@code earnedGrossSalary} was paid.
     *
     * <p>Days are the figures that should reconcile, and
     * {@code reconciled} says whether they did: {@code dayWisePaidDays} and
     * {@code dayWiseLopDays} are the day column sums, against the run's own
     * {@code payableDays} and {@code lopDays}.
     *
     * <p>They can legitimately disagree, and the disagreement is worth
     * seeing rather than smoothing over. The month-level LOP formula is
     * {@code workingDays - presentDays - paidLeaveDays}
     * ({@code LopCalculationService}), which counts a day twice if the
     * employee both attended it <em>and</em> has approved paid leave
     * covering it - leave nobody cancelled when they turned up. That
     * subtracts a day of LOP the employee never actually took. Per day the
     * same arithmetic cannot go below zero, so the day column reports the
     * true count and the totals differ by exactly the number of
     * double-credited days. When {@code reconciled} is false, the
     * overlapping days are the ones to look at - and the run is the
     * optimistic one.
     */
    public record DayWiseReport(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            EmployeeStatus employmentStatus,
            int month,
            int year,
            String period,
            BigDecimal perDay,
            BigDecimal perHour,
            BigDecimal standardHoursPerDay,
            BigDecimal overtimeRateMultiplier,
            BigDecimal prorationBase,
            /** The run's own payable days - what was actually paid. */
            BigDecimal payableDays,
            /** The run's own LOP days. */
            BigDecimal lopDays,
            /** The paid fractions below, summed. */
            BigDecimal dayWisePaidDays,
            /** The LOP fractions below, summed. */
            BigDecimal dayWiseLopDays,
            /** Whether the two pairs above agree - see the class Javadoc for when they don't. */
            boolean reconciled,
            BigDecimal dayWiseWageTotal,
            BigDecimal overtimeAmountTotal,
            BigDecimal earnedGrossSalary,
            BigDecimal otAllowance,
            BigDecimal totalDeduction,
            BigDecimal netSalary,
            List<DayWiseRow> days
    ) {
    }

    /** A named deduction bucket in the company rollup. */
    public record DeductionTotal(String label, BigDecimal amount) {
    }

    /** Department/designation slice of the company rollup. */
    public record GroupTotal(
            String groupName,
            long headcount,
            BigDecimal fixedWages,
            BigDecimal earnedWages,
            BigDecimal otAmount,
            BigDecimal totalDeductions,
            BigDecimal netSalary,
            BigDecimal totalHours,
            BigDecimal overtimeHours
    ) {
    }

    /** Company-level rollup for the same month and the same filter. */
    public record CompanySummary(
            int month,
            int year,
            String period,
            long headcount,
            long employeesWithLop,
            long employeesWithOvertime,
            BigDecimal totalFixedWages,
            BigDecimal totalEarnedWages,
            BigDecimal totalBonus,
            BigDecimal totalIncentive,
            BigDecimal totalOtAmount,
            BigDecimal totalEarnings,
            BigDecimal totalHours,
            BigDecimal totalRegularHours,
            BigDecimal totalOvertimeHours,
            BigDecimal totalPayableDays,
            BigDecimal totalLopDays,
            List<DeductionTotal> deductions,
            BigDecimal totalDeductions,
            BigDecimal totalNetSalary,
            List<GroupTotal> byDepartment,
            List<GroupTotal> byDesignation
    ) {
    }
}

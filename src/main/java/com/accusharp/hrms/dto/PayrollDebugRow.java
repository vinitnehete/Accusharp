package com.accusharp.hrms.dto;

import java.math.BigDecimal;

/**
 * Everything {@code PayrollService.build()} consumed and produced for one
 * employee's period, laid flat in one row so a wrong net salary can be traced
 * back to the exact input or step that caused it, without re-deriving the
 * calculation by hand.
 *
 * <p>The {@code stored*} fields are the immutable snapshot from generation
 * time ({@code Payroll} itself); the {@code live*} fields are read fresh at
 * request time from the employee record and the company's current {@link
 * com.accusharp.hrms.entity.SalaryRule}. A payroll that still matches
 * {@code liveGrossSalary}/{@code liveRuleBasicDaPercent} etc. was computed off
 * data that hasn't since changed; a mismatch is exactly the kind of drift
 * ("we edited the rule/salary after generating, then wondered why this
 * month's numbers look off") this endpoint exists to surface.
 */
public record PayrollDebugRow(

        // ---- identity -----------------------------------------------------
        Long payrollId,
        String employeeId,
        String employeeName,
        String employeeCode,
        String companyName,
        String departmentName,
        String designationName,
        String employmentStatus,
        int revision,
        String status,
        String generatedAt,
        String generatedBy,

        // ---- attendance snapshot used for this payroll ---------------------
        Integer daysInMonth,
        Integer workingDays,
        BigDecimal presentDays,
        BigDecimal paidLeaveDays,
        BigDecimal lopDays,
        BigDecimal payableDays,
        BigDecimal totalHours,
        BigDecimal overtimeHours,
        BigDecimal perDay,
        BigDecimal perHour,

        // ---- earnings --------------------------------------------------------
        BigDecimal earnBasicDA,
        BigDecimal earnHra,
        BigDecimal earnConveyance,
        BigDecimal earnEducation,
        BigDecimal earnMedical,
        BigDecimal earnOther,
        BigDecimal bonus,
        BigDecimal incentive,
        BigDecimal otAllowance,
        BigDecimal earnGrossSalary,
        BigDecimal totalEarnings,

        // ---- deductions --------------------------------------------------------
        BigDecimal pf,
        BigDecimal earnPf,
        BigDecimal pfDeduction,
        BigDecimal esic,
        BigDecimal professionalTax,
        BigDecimal mlwf,
        BigDecimal tds,
        BigDecimal advanceDeduction,
        BigDecimal loanDeduction,
        BigDecimal fine,
        BigDecimal otherDeduction,
        BigDecimal canteen,
        BigDecimal lopDeduction,
        BigDecimal totalDeduction,
        BigDecimal netSalary,

        // ---- recovery balances either side of this period -------------------
        // Null on any payroll generated before recoveries were tracked - a
        // zero there would claim the employee owed nothing, which isn't the
        // same statement.
        BigDecimal loanOpeningBalance,
        BigDecimal loanClosingBalance,
        BigDecimal advanceOpeningBalance,
        BigDecimal advanceClosingBalance,
        BigDecimal fineOpeningBalance,
        BigDecimal fineClosingBalance,
        BigDecimal otherOpeningBalance,
        BigDecimal otherClosingBalance,
        /** Due this period but unaffordable under the net-pay floor - still owed, taken next period. */
        BigDecimal recoveryCarriedForward,
        /** What the ledger says is outstanding right now, across every type - the live counterpart to the closing balances above. */
        BigDecimal liveTotalOutstanding,

        // ---- employee master data: as stored on the payroll vs. right now --
        BigDecimal storedGrossSalary,
        BigDecimal liveGrossSalary,
        BigDecimal storedPfBasic,
        BigDecimal livePfBasic,

        // ---- salary rule: as stored on the payroll vs. the company's rule right now --
        BigDecimal storedRuleBasicDaPercent,
        BigDecimal liveRuleBasicDaPercent,
        BigDecimal storedRulePfPercent,
        BigDecimal liveRulePfPercent,
        BigDecimal storedRuleEsicPercent,
        BigDecimal liveRuleEsicPercent,
        /** DAY_WISE's payable-day base, and the days+leave cap for everyone's overtime base. Null on a payroll generated before this was tracked. */
        Integer storedRuleDayWiseDaysInMonth,
        Integer liveRuleDayWiseDaysInMonth,
        /** Feeds perHour for every employment status, and the overtime-hour base for DAY_WISE. */
        BigDecimal storedRuleStandardHoursPerDay,
        BigDecimal liveRuleStandardHoursPerDay,
        /** Multiplies overtimeHours x perHour into otAllowance for every overtime-eligible employee. */
        BigDecimal storedRuleOvertimeRateMultiplier,
        BigDecimal liveRuleOvertimeRateMultiplier,
        /** The net-pay floor recoveries were held to. Null on a payroll generated before recoveries were tracked. */
        BigDecimal storedRuleRecoveryMinimumNetPercent,
        BigDecimal liveRuleRecoveryMinimumNetPercent,

        /** True when the employee's master salary data changed after this payroll was generated. */
        boolean masterDataDrifted,
        /** True when the company's SalaryRule changed after this payroll was generated. */
        boolean ruleDrifted
) {
}

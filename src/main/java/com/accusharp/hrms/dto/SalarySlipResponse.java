package com.accusharp.hrms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Print-ready salary slip assembled from an immutable payroll snapshot.
 * Nothing here is recomputed - it is exactly what payroll recorded.
 */
public record SalarySlipResponse(
        String companyName,
        String employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String designationName,
        String period,
        AttendanceSummary attendance,
        List<Line> earnings,
        List<Line> deductions,
        BigDecimal totalEarnings,
        BigDecimal totalDeductions,
        BigDecimal netSalary,
        String netSalaryInWords,
        /** Loan/advance/fine/other balances either side of this period; empty when nothing was owed or recovered. */
        List<RecoveryBalance> recoveryBalances,
        int revision,
        Instant generatedAt
) {

    public record Line(String label, BigDecimal amount) {
    }

    /**
     * What the employee owed before this period, what it recovered, and what
     * is left - the "opening balance / deduction / remaining" line a loan
     * needs on the slip, straight from the payroll snapshot so a reprint
     * shows the balance as it stood then.
     */
    public record RecoveryBalance(
            String label,
            BigDecimal openingBalance,
            BigDecimal recovered,
            BigDecimal closingBalance
    ) {
    }

    public record AttendanceSummary(
            int daysInMonth,
            int workingDays,
            BigDecimal presentDays,
            BigDecimal paidLeaveDays,
            BigDecimal lopDays,
            BigDecimal payableDays,
            BigDecimal totalHours,
            BigDecimal overtimeHours
    ) {
    }
}

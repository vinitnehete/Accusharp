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
        int revision,
        Instant generatedAt
) {

    public record Line(String label, BigDecimal amount) {
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

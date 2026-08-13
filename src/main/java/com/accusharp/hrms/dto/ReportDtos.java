package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response shapes for the reporting and dashboard endpoints. */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record MonthlyAttendanceRow(
            String userId,
            String employeeName,
            String departmentName,
            long workingDays,
            BigDecimal presentDays,
            BigDecimal absentDays,
            BigDecimal leaveDays,
            BigDecimal lopDays,
            long lateCount,
            long earlyExitCount,
            long invalidPunches,
            BigDecimal totalHours,
            BigDecimal overtimeHours
    ) {
    }

    public record LeaveBalanceRow(
            String userId,
            String employeeName,
            Map<LeaveType, BigDecimal> quota,
            Map<LeaveType, BigDecimal> used,
            Map<LeaveType, BigDecimal> available
    ) {
    }

    public record PayrollRow(
            String userId,
            String employeeName,
            String departmentName,
            BigDecimal payableDays,
            BigDecimal lopDays,
            BigDecimal totalEarnings,
            BigDecimal pfDeduction,
            BigDecimal esic,
            BigDecimal professionalTax,
            BigDecimal mlwf,
            BigDecimal totalDeductions,
            BigDecimal netSalary
    ) {
    }

    public record PayrollCostGroup(
            String groupName,
            long employeeCount,
            BigDecimal totalEarnings,
            BigDecimal totalDeductions,
            BigDecimal netSalary
    ) {
    }

    public record StatutoryRow(
            String userId,
            String employeeName,
            BigDecimal base,
            BigDecimal amount
    ) {
    }

    public record ExceptionRow(
            String userId,
            String employeeName,
            LocalDate date,
            String detail
    ) {
    }

    public record DashboardResponse(
            LocalDate asOf,
            Cards cards,
            Charts charts
    ) {
    }

    public record Cards(
            long totalEmployees,
            long presentToday,
            long absentToday,
            long employeesOnLeave,
            long pendingLeaveRequests,
            long unscheduledTomorrow,
            long payrollGeneratedThisMonth,
            List<PersonEvent> upcomingBirthdays,
            List<PersonEvent> upcomingWorkAnniversaries
    ) {
    }

    public record PersonEvent(String userId, String employeeName, LocalDate date, Integer years) {
    }

    public record Charts(
            List<PointLong> attendanceTrend,
            List<PointLong> departmentStrength,
            List<PointAmount> payrollCost,
            List<PointAmount> leaveUsage
    ) {
    }

    public record PointLong(String label, long value) {
    }

    public record PointAmount(String label, BigDecimal value) {
    }
}

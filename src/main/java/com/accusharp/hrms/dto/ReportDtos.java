package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.SalaryRevisionReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // ---- payroll registers -------------------------------------------------

    /**
     * One line of the payroll register - the full earning and deduction
     * breakup a run paid an employee, alongside the CTC it was prorated
     * from. Read entirely off the {@code Payroll} snapshot; the statutory
     * identifiers come from the employee master, which is the only place
     * they exist.
     */
    public record PayrollRegisterRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            String categoryName,
            EmployeeStatus employmentStatus,
            LocalDate joiningDate,
            String uanNo,
            String esicIpNo,
            int month,
            int year,
            String period,
            int revision,
            BigDecimal grossSalary,
            BigDecimal grossSalaryWage,
            BigDecimal pfBasic,
            BigDecimal payableDays,
            BigDecimal lopDays,
            BigDecimal earnBasicDA,
            BigDecimal earnHra,
            BigDecimal earnConveyance,
            BigDecimal earnEducation,
            BigDecimal earnMedical,
            BigDecimal earnOther,
            BigDecimal otAllowance,
            BigDecimal bonus,
            BigDecimal incentive,
            BigDecimal totalEarnings,
            BigDecimal pfDeduction,
            BigDecimal esic,
            BigDecimal professionalTax,
            BigDecimal mlwf,
            BigDecimal tds,
            BigDecimal advanceDeduction,
            BigDecimal loanDeduction,
            BigDecimal canteen,
            BigDecimal totalDeductions,
            BigDecimal netSalary
    ) {
    }

    /** One line of the payslip register - what each employee's slip for the period says, in bulk. */
    public record PayslipRegisterRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            String period,
            int revision,
            Instant generatedAt,
            BigDecimal payableDays,
            BigDecimal lopDays,
            BigDecimal totalEarnings,
            BigDecimal totalDeductions,
            BigDecimal netSalary,
            String netSalaryInWords
    ) {
    }

    /**
     * A salary revision, plus the arrears exposure it created.
     *
     * <p>{@code periodsPaidAtOldRate} lists periods already generated at the
     * superseded gross that end on or after the effective date - a
     * retrospective raise. {@code estimatedArrears} is what regenerating
     * those periods would add: payroll's own {@code regenerate} splits the
     * period at the effective date (see
     * {@code PayrollService.resolveGrossSalarySegments}) and is the thing
     * that actually settles the difference, so the figure here is an
     * indication of exposure, not a second calculation of pay.
     */
    public record SalaryRevisionRow(
            Long id,
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            LocalDate effectiveDate,
            BigDecimal previousGrossSalary,
            BigDecimal newGrossSalary,
            BigDecimal difference,
            BigDecimal hikePercent,
            SalaryRevisionReason reason,
            String remarks,
            String revisedBy,
            Instant createdAt,
            List<String> periodsPaidAtOldRate,
            BigDecimal estimatedArrears
    ) {
    }

    /** One credit line of the bank advice. */
    public record BankTransferRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String bankAccountNo,
            String bankIfscNo,
            String period,
            BigDecimal netSalary,
            String remarks
    ) {
    }

    /** The bank advice for a period: every credit line plus the control totals a bank file is checked against. */
    public record BankTransferAdvice(
            String period,
            long employeeCount,
            long payableCount,
            long missingBankDetailsCount,
            BigDecimal totalAmount,
            List<BankTransferRow> rows
    ) {
    }

    /**
     * The full &amp; final worksheet for one leaver. Every figure is read off
     * an existing record - the last generated payroll, the leave balance, the
     * employee master. Nothing settles anything: leave encashment and notice
     * recovery have no policy modelled in this system, so they are absent
     * rather than guessed at.
     */
    public record FullAndFinalRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            LocalDate joiningDate,
            LocalDate relievingDate,
            BigDecimal yearsOfService,
            String lastPaidPeriod,
            BigDecimal lastPaidNetSalary,
            boolean finalMonthPayrollGenerated,
            BigDecimal grossSalary,
            BigDecimal basicDA,
            BigDecimal paidLeaveBalanceDays,
            BigDecimal lastRunAdvanceDeduction,
            BigDecimal lastRunLoanDeduction,
            boolean gratuityEligible,
            BigDecimal gratuityAccrued
    ) {
    }

    // ---- attendance & leave drill-downs ------------------------------------

    /** One exceptional attendance day - late in, early out, missed punch, or an unexplained absence. */
    public record AttendanceExceptionRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            LocalDate date,
            String dayOfWeek,
            String shiftCode,
            AttendanceStatus status,
            LocalDateTime firstIn,
            LocalDateTime lastOut,
            BigDecimal workingHours,
            int lateMinutes,
            int earlyExitMinutes,
            boolean invalidPunch,
            /** Comma-separated exception labels, so one row can carry several. */
            String exceptions,
            String recordStatus,
            boolean locked,
            String remarks
    ) {
    }

    /** One overtime day, with what that day's overtime was worth. */
    public record OvertimeRegisterRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            LocalDate date,
            String dayOfWeek,
            String shiftCode,
            LocalDateTime firstIn,
            LocalDateTime lastOut,
            BigDecimal workingHours,
            BigDecimal overtimeHours,
            BigDecimal perHour,
            BigDecimal overtimeRateMultiplier,
            BigDecimal overtimeAmount
    ) {
    }

    /** One leave request, whatever became of it. */
    public record LeaveTransactionRow(
            Long id,
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            LeaveType leaveType,
            boolean paid,
            LocalDate fromDate,
            LocalDate toDate,
            LeaveDuration duration,
            BigDecimal totalDays,
            LeaveStatus status,
            LeaveOrigin origin,
            String reason,
            String supervisorId,
            String approverId,
            String approvalComments,
            Instant appliedAt,
            Instant decidedAt
    ) {
    }

    // ---- statutory ---------------------------------------------------------

    /** One member line of the PF ECR. Column names follow the EPFO ECR layout. */
    public record PfEcrRow(
            String uanNo,
            String userId,
            String employeeCode,
            String employeeName,
            BigDecimal grossWages,
            BigDecimal epfWages,
            BigDecimal epsWages,
            BigDecimal edliWages,
            BigDecimal employeeContribution,
            BigDecimal epsContribution,
            BigDecimal employerEpfContribution,
            /** Non-contributory period - the month's LOP days. */
            BigDecimal ncpDays,
            BigDecimal refundOfAdvances
    ) {
    }

    /** One insured-person line of the ESI monthly return. */
    public record EsiReturnRow(
            String esicIpNo,
            String userId,
            String employeeCode,
            String employeeName,
            BigDecimal daysWorked,
            BigDecimal totalMonthlyWages,
            BigDecimal employeeContribution,
            BigDecimal employerContribution,
            BigDecimal totalContribution
    ) {
    }

    /** One line of the professional tax register. */
    public record ProfessionalTaxRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            BigDecimal grossSalary,
            BigDecimal earnedGross,
            BigDecimal professionalTax
    ) {
    }

    /** Quarterly TDS position per employee - the figures Form 24Q Annexure I is filed from. */
    public record Tds24qRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            int quarter,
            String quarterLabel,
            String financialYear,
            BigDecimal grossEarnings,
            BigDecimal tdsDeducted,
            List<MonthAmount> monthly
    ) {
    }

    public record MonthAmount(String period, BigDecimal earnings, BigDecimal tds) {
    }

    /** Gratuity liability accrued for one employee as at a date. */
    public record GratuityAccrualRow(
            String userId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String designationName,
            LocalDate joiningDate,
            LocalDate asOf,
            BigDecimal yearsOfService,
            int completedYears,
            boolean eligible,
            BigDecimal lastDrawnBasicDA,
            BigDecimal accruedAmount
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

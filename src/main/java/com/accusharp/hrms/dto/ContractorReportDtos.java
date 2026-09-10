package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The three shapes the contractor attendance reports come in.
 *
 * <p>Every one of them carries {@code contractorName} on the row rather than
 * only in an enclosing object: a company routinely engages several
 * contractors, these reports are read (and exported to CSV) across all of
 * them at once, and a grouping that only survives in the JSON envelope does
 * not survive the export.
 *
 * <p>Kept separate from {@link ReportDtos} deliberately. Those rows describe
 * the company's own payroll population and several of them carry money;
 * these describe a population this company does not pay, and none of them
 * ever will.
 */
public final class ContractorReportDtos {

    private ContractorReportDtos() {
    }

    /**
     * One line per worker for the month - what the contractor invoices and
     * runs their own payroll from.
     *
     * <p>Read straight off {@code MonthlyAttendanceSummary}, the same cached
     * rollup {@code ReportService.monthlyAttendanceReport} reads for company
     * staff, so a contractor's figures can never disagree with what the
     * attendance console shows for the same person.
     */
    public record ContractorAttendanceRow(
            Long contractorId,
            String contractorCode,
            String contractorName,
            String userId,
            String employeeCode,
            String employeeName,
            String designationName,
            String supervisorName,
            long workingDays,
            BigDecimal presentDays,
            BigDecimal absentDays,
            BigDecimal halfDays,
            BigDecimal leaveDays,
            long holidayDays,
            long weekOffDays,
            long lateCount,
            long earlyExitCount,
            long invalidPunches,
            BigDecimal totalHours,
            BigDecimal overtimeHours
    ) {
    }

    /**
     * The day-by-day register behind one month's summary - the evidence a
     * contractor disputes or accepts a figure against.
     */
    public record ContractorDailyRow(
            Long contractorId,
            String contractorName,
            String userId,
            String employeeCode,
            String employeeName,
            LocalDate date,
            String shiftCode,
            LocalDateTime firstIn,
            LocalDateTime lastOut,
            BigDecimal workingHours,
            BigDecimal overtimeHours,
            int lateMinutes,
            int earlyExitMinutes,
            boolean weekOff,
            boolean holiday,
            AttendanceStatus status,
            /** GENERATED or MANUAL - a corrected day is disclosed, not quietly presented as a device reading. */
            AttendanceRecordStatus recordStatus
    ) {
    }

    /**
     * One line per contractor - the cover sheet. Totals exactly the rows
     * {@link ContractorAttendanceRow} lists, never a separate calculation.
     */
    public record ContractorSummaryRow(
            Long contractorId,
            String contractorCode,
            String contractorName,
            String contactPerson,
            String email,
            long workerCount,
            /** Workers with no generated attendance at all for the period - the figure that invalidates a report. */
            long workersWithoutAttendance,
            long workingDays,
            BigDecimal presentDays,
            BigDecimal absentDays,
            BigDecimal leaveDays,
            BigDecimal totalHours,
            BigDecimal overtimeHours
    ) {
    }

    /** A contractor's month: the cover line, then every worker on it. */
    public record ContractorMonthlyReport(
            String month,
            ContractorSummaryRow summary,
            List<ContractorAttendanceRow> rows
    ) {
    }
}

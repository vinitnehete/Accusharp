package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ContractorReportDtos;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.contractor.ContractorEmployeeService;
import com.accusharp.hrms.service.contractor.ContractorService;
import com.accusharp.hrms.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client company actually sends its labour contractor: the month's
 * attendance for the workers that contractor deployed, so the contractor can
 * run their own payroll from it.
 *
 * <p>Like every other report in this package it <b>aggregates, never
 * calculates</b> - the rows come straight off {@link MonthlyAttendanceSummary}
 * and {@link DailyAttendance}, the same stored artifacts the attendance
 * console shows. A contractor's copy of a figure can therefore never disagree
 * with what HR sees on screen for the same person on the same day, which is
 * the property that makes the report worth sending at all.
 *
 * <p>The summaries are resynced first, exactly as
 * {@code ReportService.monthlyAttendanceReport} does - but through
 * {@link AttendanceService#syncSummaries(YearMonth, List)}, because the
 * no-argument form deliberately skips contractor workers (see
 * {@code EmployeeService#getActiveEntities()}).
 *
 * <p>Every read enters through {@link ContractorService#getEntityById} or
 * {@link ContractorEmployeeService#activeEntitiesOf}, so a cross-company
 * contractor id is a 404 before a single attendance row is touched.
 */
@Service
@RequiredArgsConstructor
public class ContractorAttendanceReportService {

    private static final int DAY_SCALE = 1;
    private static final int HOUR_SCALE = 2;

    private final ContractorService contractorService;
    private final ContractorEmployeeService contractorEmployeeService;
    private final AttendanceService attendanceService;
    private final MonthlyAttendanceSummaryRepository summaryRepository;
    private final DailyAttendanceRepository dailyAttendanceRepository;

    // ---- one contractor, one month -----------------------------------------

    /** The cover line plus one row per worker - the report a contractor is sent. */
    @Transactional
    public ContractorReportDtos.ContractorMonthlyReport monthlyReport(Long contractorId, YearMonth month) {
        Contractor contractor = contractorService.getEntityById(contractorId);
        List<Employee> workers = contractorEmployeeService.activeEntitiesOf(contractorId);
        Map<String, MonthlyAttendanceSummary> summaries = refreshedSummaries(workers, month);

        List<ContractorReportDtos.ContractorAttendanceRow> rows = workers.stream()
                .map(worker -> row(contractor, worker, summaries.get(worker.getUserId())))
                .sorted(Comparator.comparing(ContractorReportDtos.ContractorAttendanceRow::employeeCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new ContractorReportDtos.ContractorMonthlyReport(
                month.toString(), summarise(contractor, rows, summaries, workers), rows);
    }

    /** Just the worker rows - what the grid binds to. */
    @Transactional
    public List<ContractorReportDtos.ContractorAttendanceRow> monthlyRows(Long contractorId, YearMonth month) {
        return monthlyReport(contractorId, month).rows();
    }

    /**
     * Every contractor's month on one sheet, one line each.
     *
     * <p>This is the view that answers "a company has more than two
     * contractors": it is the only place the engaging company sees them side
     * by side, and {@code workersWithoutAttendance} on each line is the figure
     * that says a contractor's report is not ready to send yet.
     */
    @Transactional
    public List<ContractorReportDtos.ContractorSummaryRow> allContractorsSummary(YearMonth month) {
        List<ContractorReportDtos.ContractorSummaryRow> summaries = new ArrayList<>();
        for (Contractor contractor : contractorService.getActiveEntities()) {
            summaries.add(monthlyReport(contractor.getId(), month).summary());
        }
        return summaries;
    }

    // ---- the day-by-day register -------------------------------------------

    /**
     * Every stored day for the contractor's workforce over a window - the
     * evidence a monthly figure is disputed or accepted against.
     *
     * <p>One query for the whole batch rather than one per worker, the same
     * {@code IN}-clause fetch the company-wide day-level reports use.
     */
    @Transactional(readOnly = true)
    public List<ContractorReportDtos.ContractorDailyRow> dailyRegister(Long contractorId,
                                                                       LocalDate fromDate, LocalDate toDate) {
        Contractor contractor = contractorService.getEntityById(contractorId);
        List<Employee> workers = contractorEmployeeService.activeEntitiesOf(contractorId);
        if (workers.isEmpty()) {
            return List.of();
        }
        Map<String, Employee> byUserId = new LinkedHashMap<>();
        workers.forEach(worker -> byUserId.put(worker.getUserId(), worker));

        return dailyAttendanceRepository
                .findAllByUserIdInAndAttendanceDateBetweenOrderByUserIdAscAttendanceDateAsc(
                        byUserId.keySet(), fromDate, toDate).stream()
                .map(record -> {
                    Employee worker = byUserId.get(record.getUserId());
                    return new ContractorReportDtos.ContractorDailyRow(
                            contractor.getId(), contractor.getContractorName(),
                            record.getUserId(),
                            worker == null ? null : worker.getEmployeeCode(),
                            worker == null ? null : worker.getEmployeeName(),
                            record.getAttendanceDate(), record.getShiftCode(),
                            record.getFirstIn(), record.getLastOut(),
                            record.getWorkingHours(), record.getOvertimeHours(),
                            record.getLateMinutes(), record.getEarlyExitMinutes(),
                            record.isWeekOff(), record.isHoliday(),
                            record.getStatus(), record.getRecordStatus());
                })
                .toList();
    }

    // ---- exports ------------------------------------------------------------

    /**
     * The monthly sheet, with the contractor's own totals appended below a
     * blank line - the shape {@code PayrollRegisterService}'s exports use, so
     * the figure the contractor invoices against travels in the same file as
     * the rows it came from.
     */
    @Transactional
    public String monthlyCsv(Long contractorId, YearMonth month) {
        ContractorReportDtos.ContractorMonthlyReport report = monthlyReport(contractorId, month);
        CsvWriter csv = new CsvWriter().header(
                "Contractor Code", "Contractor", "Worker Code", "User ID", "Worker Name", "Trade",
                "Supervisor", "Month", "Working Days", "Present Days", "Absent Days", "Half Days",
                "Leave Days", "Holidays", "Week Offs", "Late Count", "Early Exit Count",
                "Invalid Punches", "Total Hours", "Overtime Hours");

        for (ContractorReportDtos.ContractorAttendanceRow row : report.rows()) {
            csv.row(row.contractorCode(), row.contractorName(), row.employeeCode(), row.userId(),
                    row.employeeName(), row.designationName(), row.supervisorName(), report.month(),
                    row.workingDays(), row.presentDays(), row.absentDays(), row.halfDays(),
                    row.leaveDays(), row.holidayDays(), row.weekOffDays(), row.lateCount(),
                    row.earlyExitCount(), row.invalidPunches(), row.totalHours(), row.overtimeHours());
        }

        ContractorReportDtos.ContractorSummaryRow summary = report.summary();
        csv.blankLine();
        csv.row("TOTAL", summary.contractorName(), "", "", summary.workerCount() + " worker(s)", "", "",
                report.month(), summary.workingDays(), summary.presentDays(), summary.absentDays(), "",
                summary.leaveDays(), "", "", "", "", "", summary.totalHours(), summary.overtimeHours());
        if (summary.workersWithoutAttendance() > 0) {
            csv.row("WARNING", summary.workersWithoutAttendance()
                    + " worker(s) have no generated attendance for this period - generate it before sending");
        }
        return csv.build();
    }

    @Transactional(readOnly = true)
    public String dailyCsv(Long contractorId, LocalDate fromDate, LocalDate toDate) {
        CsvWriter csv = new CsvWriter().header(
                "Contractor", "Worker Code", "User ID", "Worker Name", "Date", "Shift", "First In",
                "Last Out", "Working Hours", "Overtime Hours", "Late (min)", "Early Out (min)",
                "Week Off", "Holiday", "Status", "Record Status");

        for (ContractorReportDtos.ContractorDailyRow row : dailyRegister(contractorId, fromDate, toDate)) {
            csv.row(row.contractorName(), row.employeeCode(), row.userId(), row.employeeName(),
                    row.date(), row.shiftCode(), row.firstIn(), row.lastOut(), row.workingHours(),
                    row.overtimeHours(), row.lateMinutes(), row.earlyExitMinutes(), row.weekOff(),
                    row.holiday(), row.status(), row.recordStatus());
        }
        return csv.build();
    }

    @Transactional
    public String allContractorsCsv(YearMonth month) {
        CsvWriter csv = new CsvWriter().header(
                "Contractor Code", "Contractor", "Contact Person", "Email", "Month", "Workers",
                "Workers Without Attendance", "Working Days", "Present Days", "Absent Days",
                "Leave Days", "Total Hours", "Overtime Hours");

        for (ContractorReportDtos.ContractorSummaryRow row : allContractorsSummary(month)) {
            csv.row(row.contractorCode(), row.contractorName(), row.contactPerson(), row.email(),
                    month.toString(), row.workerCount(), row.workersWithoutAttendance(),
                    row.workingDays(), row.presentDays(), row.absentDays(), row.leaveDays(),
                    row.totalHours(), row.overtimeHours());
        }
        return csv.build();
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Resyncs the cached summaries for this workforce, then indexes what is
     * stored by userId. The resync is what makes a report reflect an
     * attendance correction made ten minutes ago rather than whatever the
     * cache last held.
     */
    private Map<String, MonthlyAttendanceSummary> refreshedSummaries(List<Employee> workers, YearMonth month) {
        if (workers.isEmpty()) {
            return Map.of();
        }
        attendanceService.syncSummaries(month, workers);

        Map<String, MonthlyAttendanceSummary> byUserId = new HashMap<>();
        summaryRepository.findAllByMonth(month.toString())
                .forEach(summary -> byUserId.put(summary.getUserId(), summary));
        return byUserId;
    }

    /**
     * One worker's line. A worker with no generated attendance is listed with
     * zeros rather than dropped: their absence from the sheet is exactly the
     * thing the contractor would not notice, and a missing name reads as
     * "nobody deployed them" instead of "nobody generated it".
     */
    private ContractorReportDtos.ContractorAttendanceRow row(Contractor contractor, Employee worker,
                                                             MonthlyAttendanceSummary summary) {
        return new ContractorReportDtos.ContractorAttendanceRow(
                contractor.getId(), contractor.getContractorCode(), contractor.getContractorName(),
                worker.getUserId(), worker.getEmployeeCode(), worker.getEmployeeName(),
                worker.getDesignation() == null ? null : worker.getDesignation().getDesignationName(),
                worker.getSupervisor() == null ? null : worker.getSupervisor().getEmployeeName(),
                summary == null ? 0 : summary.getWorkingDays(),
                days(summary == null ? null : summary.getPresentDays()),
                days(summary == null ? null : summary.getAbsentDays()),
                days(summary == null ? null : BigDecimal.valueOf(summary.getHalfDays())),
                days(summary == null ? null : summary.getLeaveDays()),
                summary == null ? 0 : summary.getHolidayDays(),
                summary == null ? 0 : summary.getWeekOffDays(),
                summary == null ? 0 : summary.getLateCount(),
                summary == null ? 0 : summary.getEarlyExitCount(),
                summary == null ? 0 : summary.getInvalidPunches(),
                hours(summary == null ? null : summary.getTotalHours()),
                hours(summary == null ? null : summary.getOvertimeHours()));
    }

    /**
     * Totals exactly the rows above rather than re-reading the summaries, so
     * the cover line and the detail can never disagree.
     *
     * <p>{@code workingDays} is summed the same way - it is worker-days
     * expected across the whole deployment, not one worker's month, which is
     * the figure that pairs with a summed present-days count.
     */
    private ContractorReportDtos.ContractorSummaryRow summarise(
            Contractor contractor, List<ContractorReportDtos.ContractorAttendanceRow> rows,
            Map<String, MonthlyAttendanceSummary> summaries, List<Employee> workers) {

        long withoutAttendance = workers.stream()
                .filter(worker -> !summaries.containsKey(worker.getUserId()))
                .count();

        return new ContractorReportDtos.ContractorSummaryRow(
                contractor.getId(), contractor.getContractorCode(), contractor.getContractorName(),
                contractor.getContactPerson(), contractor.getEmail(),
                rows.size(), withoutAttendance,
                rows.stream().mapToLong(ContractorReportDtos.ContractorAttendanceRow::workingDays).sum(),
                total(rows, ContractorReportDtos.ContractorAttendanceRow::presentDays, DAY_SCALE),
                total(rows, ContractorReportDtos.ContractorAttendanceRow::absentDays, DAY_SCALE),
                total(rows, ContractorReportDtos.ContractorAttendanceRow::leaveDays, DAY_SCALE),
                total(rows, ContractorReportDtos.ContractorAttendanceRow::totalHours, HOUR_SCALE),
                total(rows, ContractorReportDtos.ContractorAttendanceRow::overtimeHours, HOUR_SCALE));
    }

    private BigDecimal total(List<ContractorReportDtos.ContractorAttendanceRow> rows,
                             java.util.function.Function<ContractorReportDtos.ContractorAttendanceRow,
                                     BigDecimal> field, int scale) {
        return rows.stream()
                .map(field)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal days(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(DAY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal hours(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(HOUR_SCALE, java.math.RoundingMode.HALF_UP);
    }
}

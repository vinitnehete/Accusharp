package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The day-level attendance and leave drill-downs.
 *
 * <p>{@code ReportService} already reports these as month totals per
 * employee - how many late arrivals, how many overtime hours. What it cannot
 * answer is <em>which days</em>, which is the question anyone actually
 * investigating a figure asks next. These reports open that up: one row per
 * exceptional day, one row per overtime day, one row per leave request.
 *
 * <p>Every row is a stored {@link DailyAttendance} or {@link LeaveRequest} -
 * nothing is recomputed from punches, so a drill-down always agrees with the
 * month total it was reached from, corrections included.
 */
@Service
@RequiredArgsConstructor
public class AttendanceLeaveReportService {

    private static final int SCALE = 2;

    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollService payrollService;
    private final ReportScope reportScope;

    // ---- attendance exceptions ---------------------------------------------

    /**
     * Every day in the month that needs explaining: a late arrival, an early
     * exit, a punch that could not be interpreted, or an absence on a day the
     * employee was expected to work.
     *
     * <p>A day can be several of these at once, so the reasons are collected
     * onto the single row rather than repeating the day per reason.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.AttendanceExceptionRow> attendanceExceptionReport(YearMonth month, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        if (byUserId.isEmpty()) {
            return List.of();
        }

        return dailyAttendanceRepository
                .findAllByUserIdInAndAttendanceDateBetweenOrderByUserIdAscAttendanceDateAsc(
                        byUserId.keySet(), month.atDay(1), month.atEndOfMonth())
                .stream()
                .map(record -> toExceptionRow(record, byUserId.get(record.getUserId()), names))
                .filter(row -> row != null && !row.exceptions().isEmpty())
                .toList();
    }

    private ReportDtos.AttendanceExceptionRow toExceptionRow(DailyAttendance record, Employee employee,
                                                             ReportScope.MasterNames names) {
        List<String> exceptions = new ArrayList<>();
        if (record.getLateMinutes() > 0) {
            exceptions.add("Late in " + record.getLateMinutes() + "m");
        }
        if (record.getEarlyExitMinutes() > 0) {
            exceptions.add("Early out " + record.getEarlyExitMinutes() + "m");
        }
        if (record.isInvalidPunch()) {
            exceptions.add("Missed/invalid punch");
        }
        // Only on a day the employee was expected to work - a weekly off or a
        // holiday is not an absence, and DailyAttendance#isWorkingDay is the
        // same test LOP itself is counted over.
        if (record.getStatus() == AttendanceStatus.ABSENT && record.isWorkingDay()) {
            exceptions.add("Absent");
        }
        if (exceptions.isEmpty()) {
            return null;
        }

        return new ReportDtos.AttendanceExceptionRow(
                record.getUserId(),
                employee == null ? null : employee.getEmployeeCode(),
                employee == null ? null : employee.getEmployeeName(),
                names.department(employee),
                record.getAttendanceDate(),
                dayOfWeek(record.getAttendanceDate()),
                record.getShiftCode(),
                record.getStatus(),
                record.getFirstIn(),
                record.getLastOut(),
                record.getWorkingHours(),
                record.getLateMinutes(),
                record.getEarlyExitMinutes(),
                record.isInvalidPunch(),
                String.join(", ", exceptions),
                record.getRecordStatus() == null ? null : record.getRecordStatus().name(),
                record.isLocked(),
                record.getRemarks());
    }

    /** Spreadsheet export of {@link #attendanceExceptionReport}. */
    @Transactional(readOnly = true)
    public String attendanceExceptionCsv(YearMonth month, ReportFilter filter) {
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department", "Date", "Day", "Shift",
                "Status", "First In", "Last Out", "Working Hours", "Late (min)", "Early Out (min)",
                "Invalid Punch", "Exceptions", "Record Status", "Locked", "Remarks");

        for (ReportDtos.AttendanceExceptionRow row : attendanceExceptionReport(month, filter)) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.date(), row.dayOfWeek(), row.shiftCode(), row.status(), row.firstIn(), row.lastOut(),
                    row.workingHours(), row.lateMinutes(), row.earlyExitMinutes(), row.invalidPunch(),
                    row.exceptions(), row.recordStatus(), row.locked(), row.remarks());
        }
        return csv.build();
    }

    // ---- overtime register --------------------------------------------------

    /**
     * Every day that earned overtime in the month, with what it was worth.
     *
     * <p>The rate comes from the generated payroll for the same period when
     * there is one - {@code perHour} and the multiplier are both snapshotted
     * there, so the register prices overtime at exactly the rate that was
     * paid. Before payroll runs, the hours are still listed and the amount
     * columns are simply empty rather than priced at a rate that may not end
     * up being the one used.
     *
     * <p>DAY_WISE employees are deliberately not priced per day: their
     * overtime is a monthly figure measured against the month's expected
     * hours, not a sum of daily shift overtime (see
     * {@code PayrollService.monthlyOvertimeHours}), so a per-day amount for
     * them would not add up to what they were paid.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.OvertimeRegisterRow> overtimeRegister(YearMonth month, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        if (byUserId.isEmpty()) {
            return List.of();
        }

        Map<String, Payroll> payrolls = payrollService
                .getPeriod(month.getMonthValue(), month.getYear()).stream()
                .collect(Collectors.toMap(Payroll::getEmployeeId, Function.identity(), (a, b) -> a));

        return dailyAttendanceRepository
                .findAllByUserIdInAndAttendanceDateBetweenOrderByUserIdAscAttendanceDateAsc(
                        byUserId.keySet(), month.atDay(1), month.atEndOfMonth())
                .stream()
                .filter(record -> record.getOvertimeHours() != null && record.getOvertimeHours().signum() > 0)
                .map(record -> {
                    Employee employee = byUserId.get(record.getUserId());
                    Payroll payroll = payrolls.get(record.getUserId());
                    boolean pricedPerDay = payroll != null
                            && (payroll.getEmploymentStatus() == null
                                || !payroll.getEmploymentStatus().isPaidPerAttendedDay());

                    BigDecimal perHour = pricedPerDay ? payroll.getPerHour() : null;
                    BigDecimal multiplier = pricedPerDay ? payroll.getRuleOvertimeRateMultiplier() : null;
                    BigDecimal amount = perHour == null || multiplier == null ? null
                            : record.getOvertimeHours().multiply(perHour).multiply(multiplier)
                                    .setScale(SCALE, RoundingMode.HALF_UP);

                    return new ReportDtos.OvertimeRegisterRow(
                            record.getUserId(),
                            employee == null ? null : employee.getEmployeeCode(),
                            employee == null ? null : employee.getEmployeeName(),
                            names.department(employee),
                            record.getAttendanceDate(),
                            dayOfWeek(record.getAttendanceDate()),
                            record.getShiftCode(),
                            record.getFirstIn(),
                            record.getLastOut(),
                            record.getWorkingHours(),
                            record.getOvertimeHours(),
                            perHour,
                            multiplier,
                            amount);
                })
                .toList();
    }

    /** Spreadsheet export of {@link #overtimeRegister}. */
    @Transactional(readOnly = true)
    public String overtimeRegisterCsv(YearMonth month, ReportFilter filter) {
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department", "Date", "Day", "Shift",
                "First In", "Last Out", "Working Hours", "OT Hours", "Per Hour", "OT Multiplier", "OT Amount");

        for (ReportDtos.OvertimeRegisterRow row : overtimeRegister(month, filter)) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.date(), row.dayOfWeek(), row.shiftCode(), row.firstIn(), row.lastOut(),
                    row.workingHours(), row.overtimeHours(), row.perHour(), row.overtimeRateMultiplier(),
                    row.overtimeAmount());
        }
        return csv.build();
    }

    // ---- leave transactions -------------------------------------------------

    /**
     * Every leave request overlapping the window, whatever became of it -
     * approved, rejected, cancelled or still pending.
     *
     * <p>{@code ReportService.leaveBalanceReport} answers "how much is left";
     * this answers "what happened", which is the half a balance can't show.
     * Rejected and cancelled rows are included on purpose: a transaction
     * report that only listed approvals would not reconcile against the
     * balance movements it is meant to explain.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.LeaveTransactionRow> leaveTransactionReport(LocalDate from, LocalDate to,
                                                                       ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        if (byUserId.isEmpty()) {
            return List.of();
        }

        return leaveRequestRepository
                .findAllByUserIdInAndFromDateLessThanEqualAndToDateGreaterThanEqualOrderByFromDateDesc(
                        byUserId.keySet(), to, from)
                .stream()
                .map(request -> {
                    Employee employee = byUserId.get(request.getUserId());
                    return new ReportDtos.LeaveTransactionRow(
                            request.getId(),
                            request.getUserId(),
                            employee == null ? null : employee.getEmployeeCode(),
                            employee == null ? null : employee.getEmployeeName(),
                            names.department(employee),
                            request.getLeaveType(),
                            request.getLeaveType().isPaid(),
                            request.getFromDate(),
                            request.getToDate(),
                            request.getDuration(),
                            request.getTotalDays(),
                            request.getStatus(),
                            request.getOrigin(),
                            request.getReason(),
                            request.getSupervisorId(),
                            request.getApproverId(),
                            request.getApprovalComments(),
                            request.getAppliedAt(),
                            request.getDecidedAt());
                })
                .toList();
    }

    /** Spreadsheet export of {@link #leaveTransactionReport}. */
    @Transactional(readOnly = true)
    public String leaveTransactionCsv(LocalDate from, LocalDate to, ReportFilter filter) {
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department", "Leave Type", "Paid",
                "From", "To", "Duration", "Days", "Status", "Origin", "Reason",
                "Supervisor", "Approver", "Comments", "Applied At", "Decided At");

        for (ReportDtos.LeaveTransactionRow row : leaveTransactionReport(from, to, filter)) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.leaveType(), row.paid(), row.fromDate(), row.toDate(), row.duration(),
                    row.totalDays(), row.status(), row.origin(), row.reason(), row.supervisorId(),
                    row.approverId(), row.approvalComments(), row.appliedAt(), row.decidedAt());
        }
        return csv.build();
    }

    // ---- helpers ------------------------------------------------------------

    private String dayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}

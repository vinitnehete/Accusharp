package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.PayrollAuditDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.calculation.AttendanceCalculationService;
import com.accusharp.hrms.service.leave.LeaveCalculationService;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The monthly payroll audit report: one reconcilable line per employee, a
 * day-wise drill-down behind each line, and the company rollup over the same
 * population.
 *
 * <p>Nothing here calculates pay. Every figure is read off the immutable
 * {@link Payroll} snapshot the run already wrote - the LOP, per-day rate,
 * proration base and overtime rules all come from
 * {@code PayrollService.build}, so this report can never disagree with the
 * salary slip for the same month. The two derived figures it does compute
 * are pure arithmetic over stored values and are labelled as such on the
 * DTOs: {@code regularHours} (total minus overtime) and the day-wise
 * {@code dayWage} (the snapshot's own per-day rate times the day's paid
 * fraction).
 */
@Service
@RequiredArgsConstructor
public class PayrollAuditService {

    private static final int MONEY_SCALE = 2;
    private static final int DAY_SCALE = 1;
    private static final int HOUR_SCALE = 2;

    private final PayrollService payrollService;
    private final EmployeeService employeeService;
    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final AttendanceCalculationService attendanceCalculationService;
    private final LeaveCalculationService leaveCalculationService;
    private final ReportScope reportScope;

    // ---- per-employee audit -------------------------------------------------

    /**
     * One row per employee who has a generated payroll for the period and
     * survives the filter. Employees with no payroll for the month are
     * absent rather than shown as zeroes - "not run yet" and "run, earned
     * nothing" are different facts and the audit must not blur them.
     */
    @Transactional(readOnly = true)
    public List<PayrollAuditDtos.AuditRow> auditReport(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> toAuditRow(payroll, byUserId.get(payroll.getEmployeeId()), names))
                .toList();
    }

    private PayrollAuditDtos.AuditRow toAuditRow(Payroll payroll, Employee employee,
                                                 ReportScope.MasterNames names) {
        BigDecimal prorationBase = prorationBase(payroll);
        BigDecimal totalHours = money(payroll.getTotalHours(), HOUR_SCALE);
        BigDecimal overtimeHours = money(payroll.getOvertimeHours(), HOUR_SCALE);

        return new PayrollAuditDtos.AuditRow(
                payroll.getEmployeeId(),
                payroll.getEmployeeCode(),
                payroll.getEmployeeName(),
                payroll.getDepartmentName() != null ? payroll.getDepartmentName() : names.department(employee),
                payroll.getDesignationName() != null ? payroll.getDesignationName() : names.designation(employee),
                names.category(employee),
                employee == null ? null : employee.getJoiningDate(),
                employee == null ? null : employee.getRelievingDate(),
                payroll.getEmploymentStatus(),

                payroll.getDaysInMonth(),
                payroll.getWorkingDays(),
                payroll.getPresentDays(),
                payroll.getPaidLeaveDays(),
                payroll.getLopDays(),
                payroll.getPayableDays(),
                prorationBase,

                payroll.getGrossSalary(),
                employee == null ? null : employee.getBasicDA(),
                employee == null ? null : employee.getHra(),
                employee == null ? null : employee.getConveyanceAllowance(),
                employee == null ? null : employee.getEducationAllowance(),
                employee == null ? null : employee.getMedicalAllowance(),
                employee == null ? null : employee.getOtherAllowance(),
                payroll.getGrossSalaryWage(),
                payroll.getPfBasic(),
                salaryStructureDrifted(payroll, employee),

                payroll.getEarnBasicDA(),
                payroll.getEarnHra(),
                payroll.getEarnConveyance(),
                payroll.getEarnEducation(),
                payroll.getEarnMedical(),
                payroll.getEarnOther(),
                payroll.getEarnGrossSalary(),
                payroll.getBonus(),
                payroll.getIncentive(),
                payroll.getTotalEarnings(),

                payroll.getPerDay(),
                payroll.getPerHour(),

                employee != null && employee.isOvertimeEligible(),
                payroll.getRuleStandardHoursPerDay(),
                payroll.getRuleOvertimeRateMultiplier(),
                overtimeHours,
                payroll.getOtAllowance(),

                totalHours,
                totalHours.subtract(overtimeHours).max(BigDecimal.ZERO),

                payroll.getPfDeduction(),
                payroll.getEsic(),
                payroll.getProfessionalTax(),
                payroll.getMlwf(),
                payroll.getTds(),
                payroll.getAdvanceDeduction(),
                payroll.getLoanDeduction(),
                payroll.getCanteen(),
                payroll.getLopDeduction(),
                payroll.getTotalDeduction(),

                payroll.getNetSalary(),
                payroll.getRevision(),
                payroll.getGeneratedAt());
    }

    /**
     * The denominator {@code PayrollService.build} prorated every earning
     * over: the configured payable-day base for DAY_WISE, calendar days for
     * everyone else. Both are snapshotted, so this reads rather than
     * re-decides.
     */
    private BigDecimal prorationBase(Payroll payroll) {
        // The SNAPSHOT, not the live employment type - see Payroll.wasPaidPerAttendedDay().
        // A company editing a type's pay basis must not change how an
        // already-paid period is laid out and reconciled.
        boolean dayWise = payroll.wasPaidPerAttendedDay();
        if (dayWise && payroll.getRuleDayWiseDaysInMonth() != null) {
            return BigDecimal.valueOf(payroll.getRuleDayWiseDaysInMonth()).setScale(DAY_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(payroll.getDaysInMonth()).setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    /** True when the employee master has moved since the run - see {@code PayrollAuditDtos.AuditRow}. */
    private boolean salaryStructureDrifted(Payroll payroll, Employee employee) {
        if (employee == null) {
            return false;
        }
        return differs(payroll.getGrossSalary(), employee.getGrossSalary())
                || differs(payroll.getGrossSalaryWage(), employee.getGrossSalaryWage())
                || differs(payroll.getPfBasic(), employee.getPfBasic());
    }

    private boolean differs(BigDecimal snapshot, BigDecimal live) {
        return snapshot != null && live != null && snapshot.compareTo(live) != 0;
    }

    // ---- day-wise drill-down ------------------------------------------------

    /**
     * The day-by-day wage table behind one employee's audit line.
     *
     * <p>Authorization and tenant isolation come from
     * {@link PayrollService#getCurrent} - a payroll that doesn't exist, or
     * belongs to another company, or to someone this caller may not see, all
     * yield the same 404 they would through any other payroll read.
     */
    @Transactional(readOnly = true)
    public PayrollAuditDtos.DayWiseReport dayWiseReport(String employeeId, int month, int year) {
        Payroll payroll = payrollService.getCurrent(employeeId, month, year);
        Employee employee = employeeService.getEntityByUserId(employeeId);
        YearMonth period = YearMonth.of(year, month);
        EmployedWindow window = employedWindow(employee, period);

        Map<LocalDate, DailyAttendance> stored = dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        employeeId, period.atDay(1), period.atEndOfMonth())
                .stream()
                .collect(Collectors.toMap(DailyAttendance::getAttendanceDate, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysInMonth(employeeId, period);

        boolean dayWise = payroll.wasPaidPerAttendedDay();
        BigDecimal perDay = money(payroll.getPerDay(), MONEY_SCALE);
        BigDecimal perHour = money(payroll.getPerHour(), MONEY_SCALE);
        BigDecimal multiplier = payroll.getRuleOvertimeRateMultiplier() == null
                ? BigDecimal.ONE : payroll.getRuleOvertimeRateMultiplier();
        boolean overtimePaid = payroll.getOtAllowance() != null && payroll.getOtAllowance().signum() > 0;

        List<PayrollAuditDtos.DayWiseRow> days = new ArrayList<>();
        BigDecimal wageTotal = BigDecimal.ZERO;
        BigDecimal otTotal = BigDecimal.ZERO;
        BigDecimal paidDaysTotal = BigDecimal.ZERO;
        BigDecimal lopDaysTotal = BigDecimal.ZERO;

        for (LocalDate date = period.atDay(1); !date.isAfter(period.atEndOfMonth()); date = date.plusDays(1)) {
            DailyAttendance record = stored.get(date);
            LeaveCalculationService.LeaveDay leave = leaveDays.get(date);

            BigDecimal paidFraction = paidFraction(date, record, leave, dayWise, window);
            BigDecimal lopFraction = lopFraction(record, leave, dayWise);
            BigDecimal dayWage = perDay.multiply(paidFraction).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            BigDecimal dayOvertimeHours = record == null ? BigDecimal.ZERO.setScale(HOUR_SCALE)
                    : money(record.getOvertimeHours(), HOUR_SCALE);
            // DAY_WISE overtime is a monthly figure, not the sum of each
            // day's own shift overtime (see PayrollService#monthlyOvertimeHours),
            // so there is no per-day amount to attribute for them - the
            // month's OT allowance is reported on the header instead.
            BigDecimal dayOvertimeAmount = dayWise || !overtimePaid
                    ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                    : dayOvertimeHours.multiply(perHour).multiply(multiplier)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            wageTotal = wageTotal.add(dayWage);
            otTotal = otTotal.add(dayOvertimeAmount);
            paidDaysTotal = paidDaysTotal.add(paidFraction);
            lopDaysTotal = lopDaysTotal.add(lopFraction);

            days.add(new PayrollAuditDtos.DayWiseRow(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    record == null ? null : record.getShiftCode(),
                    record != null,
                    record == null ? null : record.getStatus(),
                    leave == null ? null : leave.leaveType(),
                    record != null && record.isWeekOff(),
                    record != null && record.isHoliday(),
                    record != null && record.isWorkingDay(),
                    record == null ? null : record.getFirstIn(),
                    record == null ? null : record.getLastOut(),
                    record == null ? BigDecimal.ZERO.setScale(HOUR_SCALE) : money(record.getWorkingHours(), HOUR_SCALE),
                    dayOvertimeHours,
                    record == null ? 0 : record.getLateMinutes(),
                    record == null ? 0 : record.getEarlyExitMinutes(),
                    record != null && record.isInvalidPunch(),
                    paidFraction,
                    lopFraction,
                    dayWage,
                    dayOvertimeAmount,
                    dayWage.add(dayOvertimeAmount)));
        }

        return new PayrollAuditDtos.DayWiseReport(
                payroll.getEmployeeId(),
                payroll.getEmployeeCode(),
                payroll.getEmployeeName(),
                payroll.getDepartmentName(),
                payroll.getDesignationName(),
                payroll.getEmploymentStatus(),
                month,
                year,
                periodLabel(period),
                perDay,
                perHour,
                payroll.getRuleStandardHoursPerDay(),
                payroll.getRuleOvertimeRateMultiplier(),
                prorationBase(payroll),
                payroll.getPayableDays(),
                payroll.getLopDays(),
                paidDaysTotal.setScale(DAY_SCALE, RoundingMode.HALF_UP),
                lopDaysTotal.setScale(DAY_SCALE, RoundingMode.HALF_UP),
                sameDays(paidDaysTotal, payroll.getPayableDays())
                        && sameDays(lopDaysTotal, payroll.getLopDays()),
                wageTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                otTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                payroll.getEarnGrossSalary(),
                payroll.getOtAllowance(),
                payroll.getTotalDeduction(),
                payroll.getNetSalary(),
                days);
    }

    /**
     * How much of a day was paid, following the same two payment models
     * {@code PayrollService.build} branches on.
     *
     * <ul>
     *   <li>DAY_WISE is paid per attended working day, so only the
     *       attendance status counts: a weekly off, a holiday, or a day with
     *       no record at all is worth nothing.</li>
     *   <li>Everyone else is salaried against the full calendar month, so
     *       every day inside the employed window is paid except the LOP
     *       portion - weekly offs and holidays included, and so is a day the
     *       roster never covered. That last case matters more than it looks:
     *       LOP is only ever counted over days attendance actually recorded
     *       (see {@code LopCalculationService}), so an unrostered day is not
     *       LOP and payroll does pay it. Treating it as unpaid here would
     *       make the day table sum to less than the payable days the run
     *       actually paid - which is exactly the disagreement this report
     *       exists to rule out.</li>
     * </ul>
     *
     * <p>Days outside the employed window are unpaid for everyone: a joiner's
     * days before joining and a leaver's after relieving are the same days
     * {@code PayrollService.employedWindow} caps payable days by.
     */
    private BigDecimal paidFraction(LocalDate date, DailyAttendance record,
                                    LeaveCalculationService.LeaveDay leave, boolean dayWise,
                                    EmployedWindow window) {
        if (dayWise) {
            return record != null && record.isWorkingDay()
                    ? attendanceCalculationService.dayFraction(record.getStatus())
                            .setScale(DAY_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(DAY_SCALE);
        }
        if (!window.covers(date)) {
            return BigDecimal.ZERO.setScale(DAY_SCALE);
        }
        return BigDecimal.ONE.subtract(lopFraction(record, leave, false))
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean sameDays(BigDecimal fromDays, BigDecimal fromRun) {
        if (fromRun == null) {
            return false;
        }
        return fromDays.setScale(DAY_SCALE, RoundingMode.HALF_UP)
                .compareTo(fromRun.setScale(DAY_SCALE, RoundingMode.HALF_UP)) == 0;
    }

    /**
     * The days of this period the employee was actually on the books - the
     * same intersection of the period with [{@code joiningDate},
     * {@code relievingDate}] that {@code PayrollService.employedWindow}
     * computes, so the two never disagree about a mid-month joiner or leaver.
     */
    private EmployedWindow employedWindow(Employee employee, YearMonth period) {
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.atEndOfMonth();

        LocalDate from = employee.getJoiningDate() == null || employee.getJoiningDate().isBefore(periodStart)
                ? periodStart : employee.getJoiningDate();
        LocalDate to = employee.getRelievingDate() == null || employee.getRelievingDate().isAfter(periodEnd)
                ? periodEnd : employee.getRelievingDate();
        return new EmployedWindow(from, to);
    }

    private record EmployedWindow(LocalDate from, LocalDate to) {
        boolean covers(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /**
     * The unpaid portion of a working day: what the day was expected to be
     * worth, less what attendance credited and less approved <em>paid</em>
     * leave - the per-day form of {@code LopCalculationService}'s monthly
     * "working days - present days - paid leave". Weekly offs and holidays
     * are never LOP; neither is any day for a DAY_WISE employee, who has no
     * LOP concept at all.
     */
    private BigDecimal lopFraction(DailyAttendance record, LeaveCalculationService.LeaveDay leave,
                                   boolean dayWise) {
        if (dayWise || record == null || !record.isWorkingDay()) {
            return BigDecimal.ZERO.setScale(DAY_SCALE);
        }
        BigDecimal present = attendanceCalculationService.dayFraction(record.getStatus());
        BigDecimal paidLeave = leave != null && leave.paid() ? leave.fraction() : BigDecimal.ZERO;
        return BigDecimal.ONE.subtract(present).subtract(paidLeave)
                .max(BigDecimal.ZERO)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    // ---- company rollup -----------------------------------------------------

    /** Company-level totals over exactly the population {@link #auditReport} lists. */
    @Transactional(readOnly = true)
    public PayrollAuditDtos.CompanySummary companySummary(int month, int year, ReportFilter filter) {
        List<PayrollAuditDtos.AuditRow> rows = auditReport(month, year, filter);
        YearMonth period = YearMonth.of(year, month);

        BigDecimal totalHours = sum(rows, PayrollAuditDtos.AuditRow::totalHours, HOUR_SCALE);
        BigDecimal overtimeHours = sum(rows, PayrollAuditDtos.AuditRow::overtimeHours, HOUR_SCALE);

        List<PayrollAuditDtos.DeductionTotal> deductions = List.of(
                new PayrollAuditDtos.DeductionTotal("Provident Fund", sum(rows, PayrollAuditDtos.AuditRow::pfDeduction, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("ESIC", sum(rows, PayrollAuditDtos.AuditRow::esic, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("Professional Tax", sum(rows, PayrollAuditDtos.AuditRow::professionalTax, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("MLWF", sum(rows, PayrollAuditDtos.AuditRow::mlwf, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("TDS", sum(rows, PayrollAuditDtos.AuditRow::tds, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("Advance", sum(rows, PayrollAuditDtos.AuditRow::advanceDeduction, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("Loan", sum(rows, PayrollAuditDtos.AuditRow::loanDeduction, MONEY_SCALE)),
                new PayrollAuditDtos.DeductionTotal("Canteen", sum(rows, PayrollAuditDtos.AuditRow::canteen, MONEY_SCALE)));

        return new PayrollAuditDtos.CompanySummary(
                month,
                year,
                periodLabel(period),
                rows.size(),
                rows.stream().filter(row -> signum(row.lopDays()) > 0).count(),
                rows.stream().filter(row -> signum(row.overtimeHours()) > 0).count(),
                sum(rows, PayrollAuditDtos.AuditRow::fixedGrossWage, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::earnGrossSalary, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::bonus, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::incentive, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::otAllowance, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::totalEarnings, MONEY_SCALE),
                totalHours,
                totalHours.subtract(overtimeHours).max(BigDecimal.ZERO),
                overtimeHours,
                sum(rows, PayrollAuditDtos.AuditRow::payableDays, DAY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::lopDays, DAY_SCALE),
                deductions,
                sum(rows, PayrollAuditDtos.AuditRow::totalDeduction, MONEY_SCALE),
                sum(rows, PayrollAuditDtos.AuditRow::netSalary, MONEY_SCALE),
                group(rows, PayrollAuditDtos.AuditRow::departmentName),
                group(rows, PayrollAuditDtos.AuditRow::designationName));
    }

    private List<PayrollAuditDtos.GroupTotal> group(List<PayrollAuditDtos.AuditRow> rows,
                                                    Function<PayrollAuditDtos.AuditRow, String> classifier) {
        Map<String, List<PayrollAuditDtos.AuditRow>> grouped = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(row -> orUnassigned(classifier.apply(row))))
                .forEach(row -> grouped.computeIfAbsent(orUnassigned(classifier.apply(row)),
                        key -> new ArrayList<>()).add(row));

        return grouped.entrySet().stream()
                .map(entry -> new PayrollAuditDtos.GroupTotal(
                        entry.getKey(),
                        entry.getValue().size(),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::fixedGrossWage, MONEY_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::earnGrossSalary, MONEY_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::otAllowance, MONEY_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::totalDeduction, MONEY_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::netSalary, MONEY_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::totalHours, HOUR_SCALE),
                        sum(entry.getValue(), PayrollAuditDtos.AuditRow::overtimeHours, HOUR_SCALE)))
                .toList();
    }

    // ---- export -------------------------------------------------------------

    /** Spreadsheet export of {@link #auditReport}, with the company totals appended as a trailing block. */
    @Transactional(readOnly = true)
    public String auditCsv(int month, int year, ReportFilter filter) {
        List<PayrollAuditDtos.AuditRow> rows = auditReport(month, year, filter);
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department", "Designation", "Category",
                "Date of Joining", "Relieving Date", "Employment Type",
                "Days in Month", "Working Days", "Present Days", "Paid Leave Days", "LOP Days",
                "Payable Days", "Proration Base",
                "Fixed Gross Salary", "Fixed Basic+DA", "Fixed HRA", "Fixed Conveyance", "Fixed Education",
                "Fixed Medical", "Fixed Other", "Fixed Gross Wage", "PF Basic",
                "Earned Basic+DA", "Earned HRA", "Earned Conveyance", "Earned Education", "Earned Medical",
                "Earned Other", "Earned Gross", "Bonus", "Incentive", "Total Earnings",
                "Per Day Rate", "Per Hour Rate", "OT Eligible", "Standard Hours/Day", "OT Multiplier",
                "OT Hours", "OT Amount", "Regular Hours", "Total Hours",
                "PF", "ESIC", "Professional Tax", "MLWF", "TDS", "Advance", "Loan", "Canteen",
                "LOP Value", "Total Deductions", "Net Salary", "Revision");

        for (PayrollAuditDtos.AuditRow row : rows) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.designationName(), row.categoryName(), row.joiningDate(), row.relievingDate(),
                    row.employmentStatus(),
                    row.daysInMonth(), row.workingDays(), row.presentDays(), row.paidLeaveDays(), row.lopDays(),
                    row.payableDays(), row.prorationBase(),
                    row.fixedGrossSalary(), row.fixedBasicDA(), row.fixedHra(), row.fixedConveyance(),
                    row.fixedEducation(), row.fixedMedical(), row.fixedOther(), row.fixedGrossWage(),
                    row.fixedPfBasic(),
                    row.earnBasicDA(), row.earnHra(), row.earnConveyance(), row.earnEducation(),
                    row.earnMedical(), row.earnOther(), row.earnGrossSalary(), row.bonus(), row.incentive(),
                    row.totalEarnings(),
                    row.perDay(), row.perHour(), row.overtimeEligible(), row.standardHoursPerDay(),
                    row.overtimeRateMultiplier(), row.overtimeHours(), row.otAllowance(),
                    row.regularHours(), row.totalHours(),
                    row.pfDeduction(), row.esic(), row.professionalTax(), row.mlwf(), row.tds(),
                    row.advanceDeduction(), row.loanDeduction(), row.canteen(),
                    row.lopDeduction(), row.totalDeduction(), row.netSalary(), row.revision());
        }

        PayrollAuditDtos.CompanySummary summary = companySummary(month, year, filter);
        csv.blankLine()
                .row("Company Totals", summary.period())
                .row("Headcount", summary.headcount())
                .row("Total Fixed Wages", summary.totalFixedWages())
                .row("Total Earned Wages", summary.totalEarnedWages())
                .row("Total OT Amount", summary.totalOtAmount())
                .row("Total Earnings", summary.totalEarnings())
                .row("Total Hours", summary.totalHours())
                .row("Total OT Hours", summary.totalOvertimeHours())
                .row("Total LOP Days", summary.totalLopDays());
        summary.deductions().forEach(deduction -> csv.row(deduction.label(), deduction.amount()));
        csv.row("Total Deductions", summary.totalDeductions())
                .row("Total Net Payable", summary.totalNetSalary());

        return csv.build();
    }

    // ---- helpers ------------------------------------------------------------

    static String periodLabel(YearMonth period) {
        return period.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + period.getYear();
    }

    private <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> extractor, int scale) {
        return rows.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(scale, RoundingMode.HALF_UP);
    }

    private int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }

    private String orUnassigned(String value) {
        return value == null || value.isBlank() ? ReportScope.UNASSIGNED : value;
    }

    private BigDecimal money(BigDecimal value, int scale) {
        return (value == null ? BigDecimal.ZERO : value).setScale(scale, RoundingMode.HALF_UP);
    }
}

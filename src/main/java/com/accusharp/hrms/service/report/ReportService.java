package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.payroll.PayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only rollups over data the other modules own. Reports never calculate
 * anything new - they aggregate what attendance and payroll already recorded,
 * so a report can never disagree with a salary slip.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final PayrollService payrollService;
    private final MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;

    // ---- attendance --------------------------------------------------------

    /** Monthly attendance across the company, resynced from the stored days. */
    @Transactional
    public List<ReportDtos.MonthlyAttendanceRow> monthlyAttendanceReport(YearMonth month) {
        attendanceService.syncSummaries(month);
        Map<String, Employee> employees = activeEmployeesByUserId();

        return monthlyAttendanceSummaryRepository.findAllByMonth(month.toString()).stream()
                .filter(summary -> employees.containsKey(summary.getUserId()))
                .sorted(Comparator.comparing(MonthlyAttendanceSummary::getUserId))
                .map(summary -> {
                    Employee employee = employees.get(summary.getUserId());
                    return new ReportDtos.MonthlyAttendanceRow(
                            summary.getUserId(), employee.getEmployeeName(), departmentName(employee),
                            summary.getWorkingDays(), summary.getPresentDays(), summary.getAbsentDays(),
                            summary.getLeaveDays(), summary.getLopDays(), summary.getLateCount(),
                            summary.getEarlyExitCount(), summary.getInvalidPunches(),
                            summary.getTotalHours(), summary.getOvertimeHours());
                })
                .toList();
    }

    @Transactional
    public List<ReportDtos.ExceptionRow> lateComingReport(YearMonth month) {
        return exceptionReport(month, summary -> summary.getLateCount() > 0,
                summary -> summary.getLateCount() + " late arrival(s)");
    }

    @Transactional
    public List<ReportDtos.ExceptionRow> absentReport(YearMonth month) {
        return exceptionReport(month, summary -> summary.getAbsentDays().signum() > 0,
                summary -> summary.getAbsentDays() + " absent day(s)");
    }

    @Transactional
    public List<ReportDtos.ExceptionRow> lopReport(YearMonth month) {
        return exceptionReport(month, summary -> summary.getLopDays().signum() > 0,
                summary -> summary.getLopDays() + " LOP day(s)");
    }

    @Transactional
    public List<ReportDtos.ExceptionRow> overtimeReport(YearMonth month) {
        return exceptionReport(month, summary -> summary.getOvertimeHours().signum() > 0,
                summary -> summary.getOvertimeHours() + " overtime hour(s)");
    }

    // ---- leave -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReportDtos.LeaveBalanceRow> leaveBalanceReport(int year) {
        Map<String, Employee> employees = activeEmployeesByUserId();
        Map<String, List<LeaveBalance>> byUser = new HashMap<>();
        leaveBalanceRepository.findAllByLeaveYear(year)
                .forEach(balance -> byUser.computeIfAbsent(balance.getUserId(), key -> new java.util.ArrayList<>())
                        .add(balance));

        return employees.values().stream()
                .sorted(Comparator.comparing(Employee::getUserId))
                .map(employee -> {
                    Map<LeaveType, BigDecimal> quota = new EnumMap<>(LeaveType.class);
                    Map<LeaveType, BigDecimal> used = new EnumMap<>(LeaveType.class);
                    Map<LeaveType, BigDecimal> available = new EnumMap<>(LeaveType.class);

                    for (LeaveBalance balance : byUser.getOrDefault(employee.getUserId(), List.of())) {
                        quota.put(balance.getLeaveType(), balance.getQuota());
                        used.put(balance.getLeaveType(), balance.getUsed());
                        available.put(balance.getLeaveType(), balance.available());
                    }
                    return new ReportDtos.LeaveBalanceRow(employee.getUserId(), employee.getEmployeeName(),
                            quota, used, available);
                })
                .toList();
    }

    // ---- payroll -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReportDtos.PayrollRow> payrollReport(int month, int year) {
        Map<String, Employee> employees = activeEmployeesByUserId();
        return payrollService.getPeriod(month, year).stream()
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> new ReportDtos.PayrollRow(
                        payroll.getEmployeeId(),
                        payroll.getEmployeeName(),
                        payroll.getDepartmentName() != null ? payroll.getDepartmentName()
                                : departmentName(employees.get(payroll.getEmployeeId())),
                        payroll.getPayableDays(), payroll.getLopDays(), payroll.getTotalEarnings(),
                        payroll.getPfDeduction(), payroll.getEsic(), payroll.getProfessionalTax(),
                        payroll.getMlwf(), payroll.getTotalDeduction(), payroll.getNetSalary()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.PayrollCostGroup> departmentPayrollReport(int month, int year) {
        return groupPayroll(month, year,
                payroll -> payroll.getDepartmentName() == null ? "Unassigned" : payroll.getDepartmentName());
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.PayrollCostGroup> companyPayrollReport(int month, int year) {
        return groupPayroll(month, year,
                payroll -> payroll.getCompanyName() == null ? "Unassigned" : payroll.getCompanyName());
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.StatutoryRow> pfReport(int month, int year) {
        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> payroll.getPfDeduction() != null && payroll.getPfDeduction().signum() > 0)
                .map(payroll -> new ReportDtos.StatutoryRow(payroll.getEmployeeId(), payroll.getEmployeeName(),
                        payroll.getEarnPf(), payroll.getPfDeduction()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.StatutoryRow> professionalTaxReport(int month, int year) {
        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> payroll.getProfessionalTax() != null
                        && payroll.getProfessionalTax().signum() > 0)
                .map(payroll -> new ReportDtos.StatutoryRow(payroll.getEmployeeId(), payroll.getEmployeeName(),
                        payroll.getGrossSalary(), payroll.getProfessionalTax()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.StatutoryRow> esicReport(int month, int year) {
        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> payroll.getEsic() != null && payroll.getEsic().signum() > 0)
                .map(payroll -> new ReportDtos.StatutoryRow(payroll.getEmployeeId(), payroll.getEmployeeName(),
                        payroll.getEarnGrossSalary(), payroll.getEsic()))
                .toList();
    }

    // ---- helpers -----------------------------------------------------------

    private List<ReportDtos.PayrollCostGroup> groupPayroll(int month, int year,
                                                           Function<Payroll, String> classifier) {
        Map<String, List<Payroll>> grouped = new LinkedHashMap<>();
        payrollService.getPeriod(month, year)
                .forEach(payroll -> grouped.computeIfAbsent(classifier.apply(payroll),
                        key -> new java.util.ArrayList<>()).add(payroll));

        return grouped.entrySet().stream()
                .map(entry -> new ReportDtos.PayrollCostGroup(
                        entry.getKey(),
                        entry.getValue().size(),
                        sum(entry.getValue(), Payroll::getTotalEarnings),
                        sum(entry.getValue(), Payroll::getTotalDeduction),
                        sum(entry.getValue(), Payroll::getNetSalary)))
                .toList();
    }

    private List<ReportDtos.ExceptionRow> exceptionReport(
            YearMonth month,
            java.util.function.Predicate<MonthlyAttendanceSummary> filter,
            Function<MonthlyAttendanceSummary, String> detail) {

        attendanceService.syncSummaries(month);
        Map<String, Employee> employees = activeEmployeesByUserId();

        return monthlyAttendanceSummaryRepository.findAllByMonth(month.toString()).stream()
                .filter(summary -> employees.containsKey(summary.getUserId()))
                .filter(filter)
                .map(summary -> new ReportDtos.ExceptionRow(
                        summary.getUserId(),
                        employees.get(summary.getUserId()).getEmployeeName(),
                        month.atEndOfMonth(),
                        detail.apply(summary)))
                .toList();
    }

    private Map<String, Employee> activeEmployeesByUserId() {
        Map<String, Employee> byUserId = new LinkedHashMap<>();
        employeeService.getActiveEntities().forEach(employee -> byUserId.put(employee.getUserId(), employee));
        return byUserId;
    }

    private String departmentName(Employee employee) {
        if (employee == null || employee.getDepartment() == null) {
            return "Unassigned";
        }
        return employee.getDepartment().getDepartmentName();
    }

    private BigDecimal sum(List<Payroll> payrolls, Function<Payroll, BigDecimal> extractor) {
        return payrolls.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

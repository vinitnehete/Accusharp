package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin dashboard. Every figure is derived live from the owning module, so the
 * dashboard cannot drift from the underlying reports.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int ATTENDANCE_TREND_DAYS = 14;
    private static final int PAYROLL_COST_MONTHS = 6;

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRepository payrollRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;

    @Transactional
    public ReportDtos.DashboardResponse getDashboard(LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        List<Employee> active = employeeService.getActiveEntities();

        return new ReportDtos.DashboardResponse(today, buildCards(today, active), buildCharts(today, active));
    }

    private ReportDtos.Cards buildCards(LocalDate today, List<Employee> active) {
        Set<String> onLeaveToday = leaveRequestRepository
                .findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), today, today)
                .stream().map(LeaveRequest::getUserId).collect(Collectors.toSet());

        long presentToday = active.stream()
                .filter(employee -> isPresentOn(employee.getUserId(), today))
                .count();

        // Only people who were actually scheduled to work can be absent.
        long scheduledToday = shiftScheduleRepository
                .findAllByUserIdInAndShiftDateBetween(userIds(active), today, today).stream()
                .filter(schedule -> !schedule.isWeekOff())
                .filter(schedule -> !onLeaveToday.contains(schedule.getUserId()))
                .count();
        long absentToday = Math.max(0, scheduledToday - presentToday);

        LocalDate tomorrow = today.plusDays(1);
        Set<String> scheduledTomorrow = shiftScheduleRepository
                .findAllByUserIdInAndShiftDateBetween(userIds(active), tomorrow, tomorrow).stream()
                .map(schedule -> schedule.getUserId()).collect(Collectors.toSet());
        long unscheduledTomorrow = active.size() - scheduledTomorrow.size();

        YearMonth thisMonth = YearMonth.from(today);
        long payrollGenerated = payrollRepository.countByMonthAndYearAndStatus(
                thisMonth.getMonthValue(), thisMonth.getYear(), PayrollStatus.GENERATED);

        return new ReportDtos.Cards(
                employeeRepository.countByRecordStatus(RecordStatus.ACTIVE),
                presentToday,
                absentToday,
                onLeaveToday.size(),
                leaveRequestRepository.countByStatus(LeaveStatus.PENDING)
                        + leaveRequestRepository.countByStatus(LeaveStatus.SUPERVISOR_APPROVED),
                unscheduledTomorrow,
                payrollGenerated,
                upcomingBirthdays(today),
                upcomingAnniversaries(today));
    }

    private ReportDtos.Charts buildCharts(LocalDate today, List<Employee> active) {
        List<ReportDtos.PointLong> attendanceTrend = new ArrayList<>();
        for (int offset = ATTENDANCE_TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            long present = active.stream().filter(e -> isPresentOn(e.getUserId(), date)).count();
            attendanceTrend.add(new ReportDtos.PointLong(date.toString(), present));
        }

        Map<String, Long> byDepartment = new LinkedHashMap<>();
        active.forEach(employee -> {
            String name = employee.getDepartment() == null ? "Unassigned"
                    : employee.getDepartment().getDepartmentName();
            byDepartment.merge(name, 1L, Long::sum);
        });
        List<ReportDtos.PointLong> departmentStrength = byDepartment.entrySet().stream()
                .map(entry -> new ReportDtos.PointLong(entry.getKey(), entry.getValue()))
                .toList();

        List<ReportDtos.PointAmount> payrollCost = new ArrayList<>();
        for (int offset = PAYROLL_COST_MONTHS - 1; offset >= 0; offset--) {
            YearMonth period = YearMonth.from(today).minusMonths(offset);
            BigDecimal cost = payrollRepository.findAllByMonthAndYearAndStatus(
                            period.getMonthValue(), period.getYear(), PayrollStatus.GENERATED).stream()
                    .map(Payroll::getNetSalary)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            payrollCost.add(new ReportDtos.PointAmount(period.toString(), cost));
        }

        Map<String, BigDecimal> leaveUsage = new LinkedHashMap<>();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        leaveRequestRepository.findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), today, yearStart)
                .forEach(request -> leaveUsage.merge(request.getLeaveType().name(),
                        request.getTotalDays(), BigDecimal::add));
        List<ReportDtos.PointAmount> leaveUsagePoints = leaveUsage.entrySet().stream()
                .map(entry -> new ReportDtos.PointAmount(entry.getKey(), entry.getValue()))
                .toList();

        return new ReportDtos.Charts(attendanceTrend, departmentStrength, payrollCost, leaveUsagePoints);
    }

    private boolean isPresentOn(String userId, LocalDate date) {
        List<DailyAttendanceResponse> days = attendanceService.getDailyAttendance(userId, date, date);
        return days.stream().anyMatch(day -> day.status() == AttendanceStatus.PRESENT
                || day.status() == AttendanceStatus.HALF_DAY);
    }

    private List<ReportDtos.PersonEvent> upcomingBirthdays(LocalDate today) {
        Month month = today.getMonth();
        return employeeRepository.findBirthdaysInMonth(month.getValue()).stream()
                .filter(employee -> employee.getDateOfBirth().getDayOfMonth() >= today.getDayOfMonth())
                .map(employee -> new ReportDtos.PersonEvent(employee.getUserId(), employee.getEmployeeName(),
                        employee.getDateOfBirth().withYear(today.getYear()), null))
                .toList();
    }

    private List<ReportDtos.PersonEvent> upcomingAnniversaries(LocalDate today) {
        Month month = today.getMonth();
        LocalDate monthStart = today.withDayOfMonth(1);
        return employeeRepository.findWorkAnniversariesInMonth(month.getValue(), monthStart).stream()
                .filter(employee -> employee.getJoiningDate().getDayOfMonth() >= today.getDayOfMonth())
                .map(employee -> new ReportDtos.PersonEvent(employee.getUserId(), employee.getEmployeeName(),
                        employee.getJoiningDate().withYear(today.getYear()),
                        today.getYear() - employee.getJoiningDate().getYear()))
                .toList();
    }

    private List<String> userIds(List<Employee> employees) {
        return employees.stream().map(Employee::getUserId).toList();
    }

    @SuppressWarnings("unused")
    private String monthLabel(YearMonth period) {
        return period.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + period.getYear();
    }
}

package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.shift.DefaultRosterResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin dashboard. Every figure is derived live from the owning module, so the
 * dashboard cannot drift from the underlying reports.
 *
 * <p>Every figure is about the employees the caller may see - the whole
 * company for HR/ADMIN, their own team for a SUPERVISOR - either by building
 * on {@code active} ({@link EmployeeService#getActiveVisibleEntities()}) or,
 * for {@code Payroll}, by routing through {@link PayrollService#getPeriodForCaller}
 * instead of {@code PayrollRepository} directly, the same choke-point pattern
 * used everywhere else in this app - see SECURITY_AUDIT.md's "list/report
 * endpoints" finding.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int ATTENDANCE_TREND_DAYS = 14;
    private static final int PAYROLL_COST_MONTHS = 6;

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final DefaultRosterResolver defaultRosterResolver;
    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final PayrollService payrollService;

    @Transactional
    public ReportDtos.DashboardResponse getDashboard(LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        // Already narrowed to who the caller may see - the same authorization
        // getDailyAttendance enforces per employee. It used to be the whole
        // company followed by a check that the caller could see every one of
        // them, which 404'd the dashboard for any SUPERVISOR in a company with
        // anybody outside their team.
        List<Employee> active = employeeService.getActiveVisibleEntities();

        // The 14-day trend and "present today" (today is always its last day)
        // share one batched attendance lookup per date instead of each
        // employee being read one day, and one call, at a time.
        Map<LocalDate, Map<String, AttendanceStatus>> statusesByDate = new LinkedHashMap<>();
        for (int offset = ATTENDANCE_TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            statusesByDate.put(date, attendanceService.statusesOn(active, date));
        }

        return new ReportDtos.DashboardResponse(today,
                buildCards(today, active, statusesByDate),
                buildCharts(today, active, statusesByDate));
    }

    private ReportDtos.Cards buildCards(LocalDate today, List<Employee> active,
                                        Map<LocalDate, Map<String, AttendanceStatus>> statusesByDate) {
        Set<String> visibleUserIds = Set.copyOf(userIds(active));

        Set<String> onLeaveToday = leaveRequestRepository
                .findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), today, today)
                .stream().map(LeaveRequest::getUserId)
                .filter(visibleUserIds::contains)
                .collect(Collectors.toSet());

        long presentToday = countPresent(active, statusesByDate.get(today));

        // Only people who were actually scheduled to work can be absent.
        long scheduledToday = rosterOn(active, today).stream()
                .filter(schedule -> !schedule.isWeekOff())
                .filter(schedule -> !onLeaveToday.contains(schedule.getUserId()))
                .count();
        long absentToday = Math.max(0, scheduledToday - presentToday);

        LocalDate tomorrow = today.plusDays(1);
        Set<String> scheduledTomorrow = rosterOn(active, tomorrow).stream()
                .map(ShiftSchedule::getUserId).collect(Collectors.toSet());
        long unscheduledTomorrow = active.size() - scheduledTomorrow.size();

        YearMonth thisMonth = YearMonth.from(today);
        long payrollGenerated = payrollService.getPeriodForCaller(thisMonth.getMonthValue(), thisMonth.getYear()).size();

        long pendingLeave = leaveRequestRepository
                .findAllByStatusIn(List.of(LeaveStatus.PENDING, LeaveStatus.SUPERVISOR_APPROVED)).stream()
                .filter(request -> visibleUserIds.contains(request.getUserId()))
                .count();

        return new ReportDtos.Cards(
                active.size(),
                presentToday,
                absentToday,
                onLeaveToday.size(),
                pendingLeave,
                unscheduledTomorrow,
                payrollGenerated,
                upcomingBirthdays(today, visibleUserIds),
                upcomingAnniversaries(today, visibleUserIds));
    }

    private ReportDtos.Charts buildCharts(LocalDate today, List<Employee> active,
                                          Map<LocalDate, Map<String, AttendanceStatus>> statusesByDate) {
        Set<String> visibleUserIds = Set.copyOf(userIds(active));

        List<ReportDtos.PointLong> attendanceTrend = new ArrayList<>();
        for (int offset = ATTENDANCE_TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            long present = countPresent(active, statusesByDate.get(date));
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

        // A sum of other people's pay - empty for a viewer without PAY_READ.
        boolean seesPay = employeeService.seesEveryonesPay();
        List<ReportDtos.PointAmount> payrollCost = new ArrayList<>();
        for (int offset = PAYROLL_COST_MONTHS - 1; seesPay && offset >= 0; offset--) {
            YearMonth period = YearMonth.from(today).minusMonths(offset);
            BigDecimal cost = payrollService.getPeriodForCaller(period.getMonthValue(), period.getYear()).stream()
                    .map(Payroll::getNetSalary)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            payrollCost.add(new ReportDtos.PointAmount(period.toString(), cost));
        }

        Map<String, BigDecimal> leaveUsage = new LinkedHashMap<>();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        leaveRequestRepository.findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), today, yearStart)
                .stream()
                .filter(request -> visibleUserIds.contains(request.getUserId()))
                .forEach(request -> leaveUsage.merge(request.getLeaveType().name(),
                        request.getTotalDays(), BigDecimal::add));
        List<ReportDtos.PointAmount> leaveUsagePoints = leaveUsage.entrySet().stream()
                .map(entry -> new ReportDtos.PointAmount(entry.getKey(), entry.getValue()))
                .toList();

        return new ReportDtos.Charts(attendanceTrend, departmentStrength, payrollCost, leaveUsagePoints);
    }

    /**
     * How many of {@code active} read as present (or half-day) on one date,
     * given that date's already-batched {@code userId -> status} map from
     * {@link AttendanceService#statusesOn} - an employee absent from the map
     * (not scheduled that day) counts as not present, same as an empty
     * {@code getDailyAttendance} result did before this was batched.
     */
    private long countPresent(List<Employee> active, Map<String, AttendanceStatus> statusesForDate) {
        return active.stream()
                .map(employee -> statusesForDate.get(employee.getUserId()))
                .filter(status -> status == AttendanceStatus.PRESENT || status == AttendanceStatus.HALF_DAY)
                .count();
    }

    private List<ReportDtos.PersonEvent> upcomingBirthdays(LocalDate today, Set<String> visibleUserIds) {
        Month month = today.getMonth();
        return employeeRepository.findBirthdaysInMonth(month.getValue()).stream()
                .filter(employee -> visibleUserIds.contains(employee.getUserId()))
                .filter(employee -> employee.getDateOfBirth().getDayOfMonth() >= today.getDayOfMonth())
                .map(employee -> new ReportDtos.PersonEvent(employee.getUserId(), employee.getEmployeeName(),
                        employee.getDateOfBirth().withYear(today.getYear()), null))
                .toList();
    }

    private List<ReportDtos.PersonEvent> upcomingAnniversaries(LocalDate today, Set<String> visibleUserIds) {
        Month month = today.getMonth();
        LocalDate monthStart = today.withDayOfMonth(1);
        return employeeRepository.findWorkAnniversariesInMonth(month.getValue(), monthStart).stream()
                .filter(employee -> visibleUserIds.contains(employee.getUserId()))
                .filter(employee -> employee.getJoiningDate().getDayOfMonth() >= today.getDayOfMonth())
                .map(employee -> new ReportDtos.PersonEvent(employee.getUserId(), employee.getEmployeeName(),
                        employee.getJoiningDate().withYear(today.getYear()),
                        today.getYear() - employee.getJoiningDate().getYear()))
                .toList();
    }

    private List<String> userIds(List<Employee> employees) {
        return employees.stream().map(Employee::getUserId).toList();
    }

    /**
     * Everyone's roster for one date - stored rows plus the days derived from
     * each employee's fixed shift and configured weekly off.
     *
     * <p>Without the merge these two cards went wrong the moment permanent
     * employees stopped having stored rows: nobody would be counted as
     * scheduled, so "absent today" would read zero however many people failed
     * to turn up, and "unscheduled tomorrow" would report the entire company.
     * Both would be confidently, quietly wrong - which on a dashboard is worse
     * than being blank.
     */
    private List<ShiftSchedule> rosterOn(List<Employee> employees, LocalDate date) {
        Map<String, List<ShiftSchedule>> storedByUser = shiftScheduleRepository
                .findAllByUserIdInAndShiftDateBetween(userIds(employees), date, date).stream()
                .collect(Collectors.groupingBy(ShiftSchedule::getUserId));

        return employees.stream()
                .flatMap(employee -> defaultRosterResolver.merge(employee,
                        storedByUser.getOrDefault(employee.getUserId(), List.of()),
                        date, date).stream())
                .toList();
    }
}

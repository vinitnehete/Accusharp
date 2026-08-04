package com.accusharp.hrms.service.attendance;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.HolidayService;
import com.accusharp.hrms.service.calculation.AttendanceCalculationService;
import com.accusharp.hrms.service.calculation.LopCalculationService;
import com.accusharp.hrms.service.leave.LeaveCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads raw biometric punches and the shift roster and produces attendance.
 *
 * <p>The roster is the source of expectation: a day the employee was not
 * scheduled on simply is not an attendance day. Holidays, weekly offs and
 * approved leave are layered on top so absence only ever means "expected to
 * work and did not".
 *
 * <p>Monthly summaries are a write-through cache, recomputed on every call and
 * overwritten - payroll reads them, nothing edits them by hand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final int DAY_SCALE = 1;
    private static final int HOUR_SCALE = 2;

    private final DeviceLogRepository deviceLogRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    private final AttendanceCalculationService attendanceCalculationService;
    private final LeaveCalculationService leaveCalculationService;
    private final LopCalculationService lopCalculationService;
    private final HolidayService holidayService;
    private final EmployeeService employeeService;

    /** Day-by-day attendance for an arbitrary window. */
    @Transactional(readOnly = true)
    public List<DailyAttendanceResponse> getDailyAttendance(String userId, LocalDate fromDate, LocalDate toDate) {
        employeeService.getEntityByUserId(userId);
        if (fromDate.isAfter(toDate)) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }

        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(fromDate, toDate);
        List<ShiftSchedule> roster = shiftScheduleRepository
                .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(userId, fromDate, toDate);

        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysInMonth(userId, YearMonth.from(fromDate));

        return roster.stream()
                .map(schedule -> calculate(userId, schedule, holidays, leaveDays))
                .toList();
    }

    /**
     * The month's attendance report. Also refreshes the cached summary payroll
     * consumes, so generating salary never sees stale numbers.
     */
    @Transactional
    public MonthlyAttendanceResponse getMonthlyAttendance(String userId, YearMonth month) {
        Employee employee = employeeService.getEntityByUserId(userId);

        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(first, last);
        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysInMonth(userId, month);

        List<ShiftSchedule> roster = shiftScheduleRepository
                .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(userId, first, last);

        List<DailyAttendanceResponse> days = new ArrayList<>(roster.size());
        Set<LocalDate> workingDates = new HashSet<>();

        for (ShiftSchedule schedule : roster) {
            days.add(calculate(userId, schedule, holidays, leaveDays));
            if (!schedule.isWeekOff() && !holidays.contains(schedule.getShiftDate())) {
                workingDates.add(schedule.getShiftDate());
            }
        }

        long workingDays = workingDates.size();
        BigDecimal presentDays = days.stream()
                .filter(day -> workingDates.contains(day.attendanceDate()))
                .map(day -> attendanceCalculationService.dayFraction(day.status()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);

        BigDecimal leaveDayCount = leaveCalculationService.totalLeaveDays(leaveDays, workingDates);
        BigDecimal paidLeaveDays = leaveCalculationService.paidLeaveDays(leaveDays, workingDates);

        BigDecimal absentDays = BigDecimal.valueOf(workingDays)
                .subtract(presentDays)
                .subtract(leaveDayCount)
                .max(BigDecimal.ZERO)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);

        long halfDays = days.stream().filter(day -> day.status() == AttendanceStatus.HALF_DAY).count();
        long holidayDays = roster.stream().filter(s -> holidays.contains(s.getShiftDate())).count();
        long weekOffDays = roster.stream().filter(ShiftSchedule::isWeekOff).count();
        long lateCount = days.stream().filter(day -> day.lateMinutes() > 0).count();
        long earlyExitCount = days.stream().filter(day -> day.earlyExitMinutes() > 0).count();
        long invalidPunches = days.stream().filter(DailyAttendanceResponse::invalidPunch).count();

        BigDecimal totalHours = sumHours(days, DailyAttendanceResponse::workingHours);
        BigDecimal overtimeHours = sumHours(days, DailyAttendanceResponse::overtimeHours);

        BigDecimal lopDays = lopCalculationService.calculateLopDays(
                BigDecimal.valueOf(workingDays), presentDays, paidLeaveDays);

        saveSummary(userId, month, workingDays, presentDays, absentDays, halfDays, leaveDayCount,
                holidayDays, weekOffDays, lateCount, earlyExitCount, invalidPunches,
                totalHours, overtimeHours, lopDays);

        log.info("attendance.monthly userId={} month={} workingDays={} presentDays={} lopDays={}",
                userId, month, workingDays, presentDays, lopDays);

        return new MonthlyAttendanceResponse(userId, employee.getEmployeeName(), month, workingDays,
                presentDays, absentDays, halfDays, leaveDayCount, holidayDays, weekOffDays, lateCount,
                earlyExitCount, invalidPunches, totalHours, overtimeHours, lopDays, days);
    }

    /** Recomputes and returns the cached summary payroll reads. */
    @Transactional
    public MonthlyAttendanceSummary refreshSummary(String userId, YearMonth month) {
        getMonthlyAttendance(userId, month);
        return monthlyAttendanceSummaryRepository.findByUserIdAndMonth(userId, month.toString())
                .orElseThrow(() -> new IllegalStateException("Summary was not persisted for " + userId));
    }

    @Transactional
    public List<MonthlyAttendanceSummary> refreshAllSummaries(YearMonth month) {
        return employeeService.getActiveEntities().stream()
                .map(employee -> refreshSummary(employee.getUserId(), month))
                .toList();
    }

    // ---- internals ---------------------------------------------------------

    private DailyAttendanceResponse calculate(String userId, ShiftSchedule schedule, Set<LocalDate> holidays,
                                              Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays) {
        LocalDate date = schedule.getShiftDate();
        LocalDateTime windowStart = attendanceCalculationService.windowStart(date, schedule.getShift());
        LocalDateTime windowEnd = attendanceCalculationService.windowEnd(date, schedule.getShift());

        List<DeviceLog> punches = deviceLogRepository
                .findAllByUserIdAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
                        userId, windowStart, windowEnd);

        return attendanceCalculationService.calculateDay(userId, date, schedule.getShift(), punches,
                schedule.isWeekOff(), holidays.contains(date), leaveDays.containsKey(date));
    }

    private void saveSummary(String userId, YearMonth month, long workingDays, BigDecimal presentDays,
                             BigDecimal absentDays, long halfDays, BigDecimal leaveDays, long holidayDays,
                             long weekOffDays, long lateCount, long earlyExitCount, long invalidPunches,
                             BigDecimal totalHours, BigDecimal overtimeHours, BigDecimal lopDays) {

        String monthKey = month.toString();
        MonthlyAttendanceSummary summary = monthlyAttendanceSummaryRepository
                .findByUserIdAndMonth(userId, monthKey)
                .orElseGet(() -> MonthlyAttendanceSummary.builder().userId(userId).month(monthKey).build());

        summary.setWorkingDays(workingDays);
        summary.setPresentDays(presentDays);
        summary.setAbsentDays(absentDays);
        summary.setHalfDays(halfDays);
        summary.setLeaveDays(leaveDays);
        summary.setHolidayDays(holidayDays);
        summary.setWeekOffDays(weekOffDays);
        summary.setLateCount(lateCount);
        summary.setEarlyExitCount(earlyExitCount);
        summary.setInvalidPunches(invalidPunches);
        summary.setTotalHours(totalHours);
        summary.setOvertimeHours(overtimeHours);
        summary.setLopDays(lopDays);

        monthlyAttendanceSummaryRepository.save(summary);
    }

    private BigDecimal sumHours(List<DailyAttendanceResponse> days,
                                java.util.function.Function<DailyAttendanceResponse, BigDecimal> extractor) {
        return days.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }
}

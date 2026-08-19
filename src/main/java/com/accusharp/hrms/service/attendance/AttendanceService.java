package com.accusharp.hrms.service.attendance;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.AttendanceRuleService;
import com.accusharp.hrms.service.AuditService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attendance is an explicit, stored artifact - not a view that re-derives
 * itself on every read.
 *
 * <p>The lifecycle is generate, review, correct, pay:
 * <pre>
 *   generate(month)  -&gt; one DailyAttendance row per rostered day, GENERATED
 *   correct(day)     -&gt; row becomes MANUAL and survives the next generation
 *   payroll          -&gt; reads the stored rows and locks them
 * </pre>
 *
 * <p>Two rules make the corrections stick, and both are load-bearing: a
 * regeneration preserves {@code MANUAL} rows unless told otherwise, and a
 * <em>read</em> never writes. Before this existed, both a plain monthly GET and
 * payroll itself recomputed from raw punches, so any correction an admin made
 * was destroyed by the next request that happened to touch the month.
 *
 * <p>Monthly summaries remain a cache, but of the stored days rather than of
 * raw punches - so LOP and payroll inherit corrections automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final int DAY_SCALE = 1;
    private static final int HOUR_SCALE = 2;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final DeviceLogRepository deviceLogRepository;
    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    private final AttendanceCalculationService attendanceCalculationService;
    private final LeaveCalculationService leaveCalculationService;
    private final LopCalculationService lopCalculationService;
    private final HolidayService holidayService;
    private final EmployeeService employeeService;
    private final AuditService auditService;
    private final AttendanceRuleService attendanceRuleService;

    // ---- generation --------------------------------------------------------

    /**
     * Builds the stored attendance for the period from raw punches and the
     * roster. Safe to rerun: locked days are left alone and manual corrections
     * are preserved unless {@code overwriteManual} is set.
     */
    @Transactional
    public AttendanceGenerationResponse generate(AttendanceGenerationRequest request) {
        assertHrOrAdmin(request.getGeneratedBy());

        YearMonth month = request.getMonth();
        List<Employee> employees = resolveEmployees(request.getUserIds());
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        int generated = 0;
        int manualPreserved = 0;
        int lockedSkipped = 0;
        List<String> withoutRoster = new ArrayList<>();

        // The rule and holiday calendar only vary by company, not by employee -
        // resolved once per distinct company in this batch (in practice exactly
        // one, the caller's own) instead of once per employee.
        Map<Long, CompanyGenerationContext> contextsByCompany = new HashMap<>();

        for (Employee employee : employees) {
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.computeIfAbsent(companyId,
                    id -> resolveCompanyContext(id, first, last));

            GenerationTally tally = generateFor(employee, month, request.getGeneratedBy(),
                    request.isOverwriteManual(), context.rule(), context.holidays());

            generated += tally.generated();
            manualPreserved += tally.manualPreserved();
            lockedSkipped += tally.lockedSkipped();
            if (tally.rosterDays() == 0) {
                withoutRoster.add(employee.getUserId());
            }
        }

        log.info("attendance.generate month={} employees={} generated={} manualPreserved={} lockedSkipped={}",
                month, employees.size(), generated, manualPreserved, lockedSkipped);

        return new AttendanceGenerationResponse(month, employees.size(), generated,
                manualPreserved, lockedSkipped, withoutRoster);
    }

    private CompanyGenerationContext resolveCompanyContext(Long companyId, LocalDate first, LocalDate last) {
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, first, last);
        return new CompanyGenerationContext(rule, holidays);
    }

    private GenerationTally generateFor(Employee employee, YearMonth month, String generatedBy,
                                        boolean overwriteManual, AttendanceRule rule, Set<LocalDate> holidays) {
        String userId = employee.getUserId();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(userId, first, last);
        List<WindowedSchedule> roster = windowed(userId, first, last, rule);
        Map<LocalDate, DailyAttendance> existing = indexByDate(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, first, last));

        List<DailyAttendance> toSave = new ArrayList<>();
        int generated = 0;
        int manualPreserved = 0;
        int lockedSkipped = 0;

        for (WindowedSchedule windowedSchedule : roster) {
            ShiftSchedule schedule = windowedSchedule.schedule();
            LocalDate date = schedule.getShiftDate();
            DailyAttendance current = existing.get(date);

            if (current != null && current.isLocked()) {
                lockedSkipped++;
                continue;
            }
            if (current != null && current.getRecordStatus().survivesRegeneration() && !overwriteManual) {
                manualPreserved++;
                continue;
            }

            DailyAttendanceResponse computed = computeFromPunches(userId, windowedSchedule, holidays, leaveDays, rule);
            DailyAttendance record = current != null ? current : new DailyAttendance();
            applyComputed(record, userId, schedule, computed, holidays.contains(date));

            record.setRecordStatus(AttendanceRecordStatus.GENERATED);
            record.setRemarks(null);
            record.setUpdatedBy(null);
            record.setUpdatedAt(null);
            record.setGeneratedAt(Instant.now());
            record.setGeneratedBy(generatedBy);

            toSave.add(record);
            generated++;
        }

        dailyAttendanceRepository.saveAll(toSave);
        rebuildSummary(employee, month);

        return new GenerationTally(roster.size(), generated, manualPreserved, lockedSkipped);
    }

    // ---- correction --------------------------------------------------------

    /**
     * Corrects one generated day. Supplied punch times are run back through
     * {@link AttendanceCalculationService} - the identical path a device punch
     * takes - so a hand-fixed day can never obey different rules from a
     * machine-read one.
     */
    @Transactional
    public AttendanceRecordResponse correctDay(String userId, LocalDate date,
                                               AttendanceCorrectionRequest request) {
        assertHrOrAdmin(request.getUpdatedBy());
        // Tenant check on the record being WRITTEN to, not just the caller's own identity above -
        // found during a full security audit: this method previously never resolved the target
        // userId at all, so an HR/ADMIN at any company could correct another company's attendance
        // by userId, having only proven they hold HR/ADMIN *somewhere*.
        Employee employee = employeeService.getEntityByUserId(userId);
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();

        DailyAttendance record = dailyAttendanceRepository.findByUserIdAndAttendanceDate(userId, date)
                .orElseThrow(() -> NotFoundException.of("Attendance",
                        userId + " on " + date + " - generate the month first"));

        if (record.isLocked()) {
            throw new BusinessRuleException("Attendance for " + userId + " on " + date
                    + " is locked because payroll has been generated - unlock the month first");
        }

        boolean hasPunches = request.getFirstIn() != null && request.getLastOut() != null;
        if (!hasPunches && request.getStatus() == null) {
            throw new BusinessRuleException(
                    "Provide both firstIn and lastOut, or a status to force");
        }
        if (hasPunches && !request.getLastOut().isAfter(request.getFirstIn())) {
            throw new BusinessRuleException("lastOut must be after firstIn");
        }

        ShiftSchedule schedule = shiftScheduleRepository.findByUserIdAndShiftDate(userId, date)
                .orElseThrow(() -> NotFoundException.of("Shift schedule", userId + " on " + date));

        if (hasPunches) {
            Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, date, date);
            Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                    leaveCalculationService.approvedLeaveDaysBetween(userId, date, date);
            AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);

            List<DeviceLog> corrected = List.of(
                    DeviceLog.builder().userId(userId).logDate(request.getFirstIn()).build(),
                    DeviceLog.builder().userId(userId).logDate(request.getLastOut()).build());

            DailyAttendanceResponse computed = attendanceCalculationService.calculateDay(
                    userId, date, schedule.getShift(), corrected, schedule.isWeekOff(),
                    holidays.contains(date), leaveDays.containsKey(date), rule);

            applyComputed(record, userId, schedule, computed, holidays.contains(date));

            // An explicit status still wins - the admin may know the day was
            // half a day even though the corrected times say otherwise.
            if (request.getStatus() != null) {
                record.setStatus(request.getStatus());
            }
        } else {
            applyForcedStatus(record, schedule, request.getStatus());
        }

        record.setRecordStatus(AttendanceRecordStatus.MANUAL);
        record.setInvalidPunch(false);
        record.setRemarks(request.getRemarks());
        record.setUpdatedBy(request.getUpdatedBy());
        record.setUpdatedAt(Instant.now());

        DailyAttendance saved = dailyAttendanceRepository.save(record);
        rebuildSummary(employee, YearMonth.from(date));

        log.info("attendance.correct userId={} date={} status={} by={}",
                userId, date, saved.getStatus(), request.getUpdatedBy());
        auditService.record("ATTENDANCE_CORRECT", "DailyAttendance", userId + " " + date,
                AuditOutcome.SUCCESS, "status=" + saved.getStatus() + " remarks=" + request.getRemarks());
        return AttendanceRecordResponse.of(saved);
    }

    /**
     * A day with no punches at all to correct. Hours follow the shift, because
     * declaring someone present means declaring they worked the shift.
     */
    private void applyForcedStatus(DailyAttendance record, ShiftSchedule schedule, AttendanceStatus status) {
        Shift shift = schedule.getShift();
        BigDecimal shiftHours = BigDecimal.valueOf(shift.getWorkingHours());

        BigDecimal workingHours = switch (status) {
            case PRESENT -> shiftHours;
            case HALF_DAY -> shiftHours.multiply(HALF);
            default -> BigDecimal.ZERO;
        };

        record.setStatus(status);
        record.setFirstIn(null);
        record.setLastOut(null);
        record.setWorkingHours(workingHours.setScale(HOUR_SCALE, RoundingMode.HALF_UP));
        record.setBreakHours(zeroHours());
        record.setOvertimeHours(zeroHours());
        record.setLateMinutes(0);
        record.setEarlyExitMinutes(0);
    }

    // ---- locking -----------------------------------------------------------

    /** Freezes the month so an already-paid period stays reproducible. */
    @Transactional
    public int lockMonth(String userId, YearMonth month) {
        Employee employee = employeeService.getEntityByUserId(userId); // tenant check; see unlockMonth's Javadoc
        return lockMonth(employee, month);
    }

    /**
     * Same as {@link #lockMonth(String, YearMonth)}, for a caller (e.g.
     * {@code PayrollService.build}) that already resolved and tenant-checked
     * the target {@link Employee} in the same transaction - skips the
     * otherwise-redundant repeat lookup.
     */
    @Transactional
    public int lockMonth(Employee employee, YearMonth month) {
        return setLocked(employee.getUserId(), month, true);
    }

    /**
     * Reopens a paid month for correction. The payroll already generated is not
     * touched - regenerate it afterwards to pick the corrections up.
     *
     * <p>Resolves {@code userId} through the tenant-checked
     * {@code EmployeeService} before touching anything - found missing during
     * a full security audit, alongside the identical gap in
     * {@link #correctDay}. {@code assertHrOrAdmin(actorId)} only proves the
     * <em>caller</em> holds HR/ADMIN somewhere; it says nothing about which
     * company the records being locked/unlocked belong to.
     */
    @Transactional
    public int unlockMonth(String userId, YearMonth month, String actorId) {
        assertHrOrAdmin(actorId);
        employeeService.getEntityByUserId(userId);
        log.info("attendance.unlock userId={} month={} by={}", userId, month, actorId);
        int updated = setLocked(userId, month, false);
        auditService.record("ATTENDANCE_UNLOCK", "DailyAttendance", userId + " " + month,
                AuditOutcome.SUCCESS, "daysUnlocked=" + updated);
        return updated;
    }

    private int setLocked(String userId, YearMonth month, boolean locked) {
        List<DailyAttendance> records = dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        userId, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(record -> record.isLocked() != locked)
                .peek(record -> record.setLocked(locked))
                .toList();
        dailyAttendanceRepository.saveAll(records);
        return records.size();
    }

    // ---- reads -------------------------------------------------------------

    /**
     * Day-by-day attendance for an arbitrary window: the stored record where
     * one exists, a transient preview computed from punches where it does not.
     * Nothing is written either way.
     */
    @Transactional(readOnly = true)
    public List<DailyAttendanceResponse> getDailyAttendance(String userId, LocalDate fromDate, LocalDate toDate) {
        Employee employee = employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        if (fromDate.isAfter(toDate)) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }

        Map<LocalDate, DailyAttendance> stored = indexByDate(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, fromDate, toDate));

        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, fromDate, toDate);
        // Read across the whole window, not just the first month of it.
        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(userId, fromDate, toDate);

        return windowed(userId, fromDate, toDate, rule).stream()
                .map(windowedSchedule -> {
                    DailyAttendance record = stored.get(windowedSchedule.schedule().getShiftDate());
                    return record != null
                            ? toDailyResponse(record)
                            : computeFromPunches(userId, windowedSchedule, holidays, leaveDays, rule);
                })
                .toList();
    }

    /**
     * The month's report. Reads the stored days once generated; otherwise
     * returns a preview computed from punches without persisting anything, so a
     * read can never overwrite a correction.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceResponse getMonthlyAttendance(String userId, YearMonth month) {
        Employee employee = employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        List<DailyAttendance> stored = storedDays(userId, month);

        return stored.isEmpty() ? previewMonth(employee, month) : aggregateStored(employee, month, stored);
    }

    /** The stored attendance rows for a period. */
    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> getRecords(String userId, YearMonth month) {
        employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        return storedDays(userId, month).stream().map(AttendanceRecordResponse::of).toList();
    }

    /**
     * The summary payroll consumes. Refuses rather than silently deriving one,
     * because paying against attendance nobody generated is the failure this
     * whole feature exists to prevent.
     */
    @Transactional
    public MonthlyAttendanceSummary getGeneratedSummary(String userId, YearMonth month) {
        return getGeneratedSummary(employeeService.getEntityByUserId(userId), month);
    }

    /**
     * Same as {@link #getGeneratedSummary(String, YearMonth)}, for a caller
     * (e.g. {@code PayrollService.build}) that already resolved and
     * tenant-checked the target {@link Employee} in the same transaction -
     * skips the otherwise-redundant repeat lookup.
     */
    @Transactional
    public MonthlyAttendanceSummary getGeneratedSummary(Employee employee, YearMonth month) {
        String userId = employee.getUserId();
        if (storedDays(userId, month).isEmpty()) {
            throw new BusinessRuleException("Attendance has not been generated for " + userId
                    + " for " + month + " - generate and review it before running payroll");
        }
        return rebuildSummary(employee, month);
    }

    /**
     * Refreshes the cached summaries from the stored days. Employees with no
     * generated attendance are skipped rather than rebuilt from raw punches.
     */
    @Transactional
    public List<MonthlyAttendanceSummary> syncSummaries(YearMonth month) {
        return employeeService.getActiveEntities().stream()
                .filter(employee -> !storedDays(employee.getUserId(), month).isEmpty())
                .map(employee -> rebuildSummary(employee, month))
                .toList();
    }

    /**
     * Batched form of {@link #getDailyAttendance} for a single day across
     * many employees at once - reuses the identical per-employee
     * windowing/punch computation ({@link #windowedFrom},
     * {@link #computeFromPunches}) this class already uses for a
     * single-employee read, so the status for any one employee is
     * unchanged; only how the underlying data is fetched differs - a
     * handful of {@code IN}-clause queries for the whole batch instead of
     * the same half-dozen queries repeated once per employee. Built for
     * {@code DashboardService}'s "who was present" aggregates, previously
     * the single biggest source of redundant queries in the app (one
     * {@link #getDailyAttendance} call per employee per day).
     *
     * <p>Deliberately skips the per-target {@code assertSelfOrManages} check
     * {@link #getDailyAttendance} performs on every call - the caller is
     * expected to authorize the whole batch itself, once, up front (see
     * {@code DashboardService}, and SECURITY.md's note that Dashboard/Report
     * stay company-wide for SUPERVISOR/HR/ADMIN rather than self-service
     * scoped per record).
     */
    @Transactional(readOnly = true)
    public Map<String, AttendanceStatus> statusesOn(List<Employee> employees, LocalDate date) {
        if (employees.isEmpty()) {
            return Map.of();
        }
        List<String> userIds = employees.stream().map(Employee::getUserId).toList();

        Map<String, AttendanceStatus> result = new HashMap<>();
        Map<String, DailyAttendance> storedByUser = dailyAttendanceRepository
                .findAllByUserIdInAndAttendanceDate(userIds, date).stream()
                .collect(Collectors.toMap(DailyAttendance::getUserId, Function.identity()));
        storedByUser.forEach((userId, record) -> result.put(userId, record.getStatus()));

        List<Employee> needPreview = employees.stream()
                .filter(employee -> !storedByUser.containsKey(employee.getUserId()))
                .toList();
        if (needPreview.isEmpty()) {
            return result;
        }
        List<String> previewUserIds = needPreview.stream().map(Employee::getUserId).toList();

        Map<Long, CompanyGenerationContext> contextsByCompany = new HashMap<>();
        Map<String, List<ShiftSchedule>> rosterByUser = shiftScheduleRepository
                .findAllByUserIdInAndShiftDateBetween(previewUserIds, date.minusDays(1), date.plusDays(1))
                .stream()
                .collect(Collectors.groupingBy(ShiftSchedule::getUserId));
        rosterByUser.values().forEach(list -> list.sort(Comparator.comparing(ShiftSchedule::getShiftDate)));

        Map<String, LeaveCalculationService.LeaveDay> leaveByUser =
                leaveCalculationService.approvedLeaveDayOn(previewUserIds, date);

        // Each employee's own window for this single day, computed with the
        // identical windowing rules getDailyAttendance uses - just fed a
        // pre-fetched, per-employee roster slice instead of querying one at a
        // time.
        Map<String, WindowedSchedule> windowByUser = new HashMap<>();
        for (Employee employee : needPreview) {
            String userId = employee.getUserId();
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.computeIfAbsent(companyId,
                    id -> resolveCompanyContext(id, date, date));
            List<WindowedSchedule> windows =
                    windowedFrom(rosterByUser.getOrDefault(userId, List.of()), date, date, context.rule());
            if (!windows.isEmpty()) {
                windowByUser.put(userId, windows.get(0));
            }
            // No window at all means not scheduled that day - same as
            // getDailyAttendance's windowed() producing an empty stream, left
            // out of `result` below exactly as an empty list would leave
            // isPresentOn's anyMatch false.
        }
        if (windowByUser.isEmpty()) {
            return result;
        }

        // One punch fetch spanning every scheduled employee's own window for
        // this date, then filtered back to each employee's precise window in
        // memory - the exact [start, end) test the per-employee query would
        // have applied itself.
        LocalDateTime broadStart = windowByUser.values().stream()
                .map(WindowedSchedule::windowStart).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime broadEnd = windowByUser.values().stream()
                .map(WindowedSchedule::windowEnd).max(LocalDateTime::compareTo).orElseThrow();
        Map<String, List<DeviceLog>> punchesByUser = deviceLogRepository
                .findAllByUserIdInAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
                        List.copyOf(windowByUser.keySet()), broadStart, broadEnd)
                .stream()
                .collect(Collectors.groupingBy(DeviceLog::getUserId));

        for (Employee employee : needPreview) {
            String userId = employee.getUserId();
            WindowedSchedule window = windowByUser.get(userId);
            if (window == null) {
                continue;
            }
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.get(companyId);

            List<DeviceLog> punches = punchesByUser.getOrDefault(userId, List.of()).stream()
                    .filter(log -> !log.getLogDate().isBefore(window.windowStart())
                            && log.getLogDate().isBefore(window.windowEnd()))
                    .toList();
            Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDaysForEmployee =
                    leaveByUser.containsKey(userId) ? Map.of(date, leaveByUser.get(userId)) : Map.of();

            DailyAttendanceResponse computed = computeFromPunches(userId, window, punches,
                    context.holidays(), leaveDaysForEmployee, context.rule());
            result.put(userId, computed.status());
        }

        return result;
    }

    // ---- internals ---------------------------------------------------------

    /** One day of attendance derived from the raw punches in the shift window. */
    private DailyAttendanceResponse computeFromPunches(String userId, WindowedSchedule windowedSchedule,
                                                       Set<LocalDate> holidays,
                                                       Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays,
                                                       AttendanceRule rule) {
        List<DeviceLog> punches = deviceLogRepository
                .findAllByUserIdAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
                        userId, windowedSchedule.windowStart(), windowedSchedule.windowEnd());
        return computeFromPunches(userId, windowedSchedule, punches, holidays, leaveDays, rule);
    }

    /**
     * Same computation as above, given the punches already fetched - lets a
     * batched caller ({@link #statusesOn}) supply punches it fetched with one
     * IN-clause query across many employees, pre-filtered to this employee's
     * own window, instead of this method issuing its own per-employee query.
     */
    private DailyAttendanceResponse computeFromPunches(String userId, WindowedSchedule windowedSchedule,
                                                       List<DeviceLog> punches,
                                                       Set<LocalDate> holidays,
                                                       Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays,
                                                       AttendanceRule rule) {
        ShiftSchedule schedule = windowedSchedule.schedule();
        LocalDate date = schedule.getShiftDate();

        return attendanceCalculationService.calculateDay(userId, date, schedule.getShift(), punches,
                schedule.isWeekOff(), holidays.contains(date), leaveDays.containsKey(date), rule);
    }

    /**
     * The roster for a window, each day carrying the punch window it actually
     * owns.
     *
     * <p>A night shift's raw window runs past midnight and, with an overtime
     * window on top, can reach into the hours the <em>next</em> scheduled shift
     * is already collecting punches for. Left alone, a single punch is then
     * claimed by two different days - the night shift swallows the next
     * morning's entry as its own exit, and both days count it.
     *
     * <p>So a day's window is truncated at the point the next scheduled day's
     * window opens. It only ever shrinks: where the shifts do not overlap, the
     * full overtime window survives untouched, and two consecutive night shifts
     * never collide in the first place.
     *
     * <p>The roster is read one day either side of the range so the boundary is
     * known at both ends - which is what makes a night shift on the last day of
     * the month hand over correctly to the first day of the next.
     */
    private List<WindowedSchedule> windowed(String userId, LocalDate fromDate, LocalDate toDate, AttendanceRule rule) {
        List<ShiftSchedule> roster = shiftScheduleRepository
                .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                        userId, fromDate.minusDays(1), toDate.plusDays(1));
        return windowedFrom(roster, fromDate, toDate, rule);
    }

    /**
     * Same computation as above, given the roster already fetched - lets a
     * batched caller ({@link #statusesOn}) supply one IN-clause roster fetch
     * across many employees instead of one query per employee. {@code roster}
     * must be ordered ascending by {@code shiftDate}, same as the query above.
     */
    private List<WindowedSchedule> windowedFrom(List<ShiftSchedule> roster, LocalDate fromDate, LocalDate toDate,
                                                AttendanceRule rule) {
        List<WindowedSchedule> windows = new ArrayList<>(roster.size());
        for (int i = 0; i < roster.size(); i++) {
            ShiftSchedule schedule = roster.get(i);
            LocalDate date = schedule.getShiftDate();
            if (date.isBefore(fromDate) || date.isAfter(toDate)) {
                continue;
            }

            LocalDateTime start = attendanceCalculationService.windowStart(date, schedule.getShift(), rule);
            LocalDateTime end = attendanceCalculationService.windowEnd(date, schedule.getShift());

            if (i + 1 < roster.size()) {
                ShiftSchedule next = roster.get(i + 1);
                LocalDateTime nextStart = attendanceCalculationService
                        .windowStart(next.getShiftDate(), next.getShift(), rule);
                if (nextStart.isBefore(end)) {
                    end = nextStart;
                }
            }
            windows.add(new WindowedSchedule(schedule, start, end.isBefore(start) ? start : end));
        }
        return windows;
    }

    private void applyComputed(DailyAttendance record, String userId, ShiftSchedule schedule,
                               DailyAttendanceResponse computed, boolean holiday) {
        record.setUserId(userId);
        record.setAttendanceDate(schedule.getShiftDate());
        record.setShiftCode(schedule.getShift().getShiftCode());
        record.setFirstIn(computed.firstIn());
        record.setLastOut(computed.lastOut());
        record.setWorkingHours(computed.workingHours());
        record.setBreakHours(computed.breakHours());
        record.setOvertimeHours(computed.overtimeHours());
        record.setLateMinutes(computed.lateMinutes());
        record.setEarlyExitMinutes(computed.earlyExitMinutes());
        record.setInvalidPunch(computed.invalidPunch());
        record.setWeekOff(schedule.isWeekOff());
        record.setHoliday(holiday);
        record.setStatus(computed.status());
    }

    private MonthlyAttendanceResponse previewMonth(Employee employee, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, first, last);
        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(employee.getUserId(), first, last);

        List<WindowedSchedule> roster = windowed(employee.getUserId(), first, last, rule);

        List<DailyAttendanceResponse> days = new ArrayList<>(roster.size());
        Set<LocalDate> workingDates = new HashSet<>();
        for (WindowedSchedule windowedSchedule : roster) {
            ShiftSchedule schedule = windowedSchedule.schedule();
            days.add(computeFromPunches(employee.getUserId(), windowedSchedule, holidays, leaveDays, rule));
            if (!schedule.isWeekOff() && !holidays.contains(schedule.getShiftDate())) {
                workingDates.add(schedule.getShiftDate());
            }
        }

        long holidayDays = roster.stream()
                .filter(w -> holidays.contains(w.schedule().getShiftDate())).count();
        long weekOffDays = roster.stream().filter(w -> w.schedule().isWeekOff()).count();

        return aggregate(employee, month, days, workingDates, leaveDays, holidayDays, weekOffDays);
    }

    /**
     * Rolls up the stored days. Week-off and holiday counts come from the
     * snapshotted flags rather than the status, because working a holiday
     * yields {@code PRESENT} - the day is still a holiday.
     */
    private MonthlyAttendanceResponse aggregateStored(Employee employee, YearMonth month,
                                                      List<DailyAttendance> stored) {
        return aggregate(employee, month,
                stored.stream().map(this::toDailyResponse).toList(),
                workingDatesOf(stored),
                leaveCalculationService.approvedLeaveDaysInMonth(employee.getUserId(), month),
                stored.stream().filter(DailyAttendance::isHoliday).count(),
                stored.stream().filter(DailyAttendance::isWeekOff).count());
    }

    /** Rolls the stored days up and writes the cached summary. */
    private MonthlyAttendanceSummary rebuildSummary(Employee employee, YearMonth month) {
        String userId = employee.getUserId();
        MonthlyAttendanceResponse rolled = aggregateStored(employee, month, storedDays(userId, month));

        String monthKey = month.toString();
        MonthlyAttendanceSummary summary = monthlyAttendanceSummaryRepository
                .findByUserIdAndMonth(userId, monthKey)
                .orElseGet(() -> MonthlyAttendanceSummary.builder().userId(userId).month(monthKey).build());

        summary.setWorkingDays(rolled.workingDays());
        summary.setPresentDays(rolled.presentDays());
        summary.setAbsentDays(rolled.absentDays());
        summary.setHalfDays(rolled.halfDays());
        summary.setLeaveDays(rolled.leaveDays());
        summary.setHolidayDays(rolled.holidayDays());
        summary.setWeekOffDays(rolled.weekOffDays());
        summary.setLateCount(rolled.lateCount());
        summary.setEarlyExitCount(rolled.earlyExitCount());
        summary.setInvalidPunches(rolled.invalidPunches());
        summary.setTotalHours(rolled.totalHours());
        summary.setOvertimeHours(rolled.overtimeHours());
        summary.setLopDays(rolled.lopDays());

        return monthlyAttendanceSummaryRepository.save(summary);
    }

    /** The one place month-level figures are derived, stored or preview. */
    private MonthlyAttendanceResponse aggregate(Employee employee, YearMonth month,
                                                List<DailyAttendanceResponse> days,
                                                Set<LocalDate> workingDates,
                                                Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays,
                                                long holidayDays, long weekOffDays) {

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
        long lateCount = days.stream().filter(day -> day.lateMinutes() > 0).count();
        long earlyExitCount = days.stream().filter(day -> day.earlyExitMinutes() > 0).count();
        long invalidPunches = days.stream().filter(DailyAttendanceResponse::invalidPunch).count();

        BigDecimal totalHours = sumHours(days, DailyAttendanceResponse::workingHours);
        BigDecimal overtimeHours = sumHours(days, DailyAttendanceResponse::overtimeHours);

        BigDecimal lopDays = lopCalculationService.calculateLopDays(
                BigDecimal.valueOf(workingDays), presentDays, paidLeaveDays);

        return new MonthlyAttendanceResponse(employee.getUserId(), employee.getEmployeeName(), month,
                workingDays, presentDays, absentDays, halfDays, leaveDayCount, holidayDays, weekOffDays,
                lateCount, earlyExitCount, invalidPunches, totalHours, overtimeHours, lopDays, days);
    }

    private List<DailyAttendance> storedDays(String userId, YearMonth month) {
        return dailyAttendanceRepository.findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                userId, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Expected working days read off the stored rows rather than re-read from
     * the roster - a frozen month must not shift because someone edited the
     * roster or the holiday calendar afterwards.
     */
    private Set<LocalDate> workingDatesOf(List<DailyAttendance> stored) {
        return stored.stream()
                .filter(DailyAttendance::isWorkingDay)
                .map(DailyAttendance::getAttendanceDate)
                .collect(Collectors.toSet());
    }

    private DailyAttendanceResponse toDailyResponse(DailyAttendance record) {
        return new DailyAttendanceResponse(record.getUserId(), record.getAttendanceDate(),
                record.getShiftCode(), record.getFirstIn(), record.getLastOut(), record.getWorkingHours(),
                record.getBreakHours(), record.getOvertimeHours(), record.getLateMinutes(),
                record.getEarlyExitMinutes(), record.isInvalidPunch(), record.getStatus());
    }

    private List<Employee> resolveEmployees(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return employeeService.getActiveEntities();
        }
        return userIds.stream().map(employeeService::getEntityByUserId).toList();
    }

    private Map<LocalDate, DailyAttendance> indexByDate(List<DailyAttendance> records) {
        Map<LocalDate, DailyAttendance> index = new HashMap<>();
        records.forEach(record -> index.put(record.getAttendanceDate(), record));
        return index;
    }

    private void assertHrOrAdmin(String actorId) {
        Employee actor = employeeService.getEntityByUserId(actorId);
        if (actor.getRole() != Role.HR && actor.getRole() != Role.ADMIN) {
            throw new BusinessRuleException(
                    "Generating or correcting attendance requires the HR or ADMIN role");
        }
    }

    private BigDecimal sumHours(List<DailyAttendanceResponse> days,
                                Function<DailyAttendanceResponse, BigDecimal> extractor) {
        return days.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroHours() {
        return BigDecimal.ZERO.setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }

    /** A rostered day and the punch window it exclusively owns. */
    private record WindowedSchedule(ShiftSchedule schedule, LocalDateTime windowStart,
                                    LocalDateTime windowEnd) {
    }

    private record GenerationTally(int rosterDays, int generated, int manualPreserved, int lockedSkipped) {
    }

    /** The attendance rule and holiday calendar for one company, resolved once per {@link #generate}. */
    private record CompanyGenerationContext(AttendanceRule rule, Set<LocalDate> holidays) {
    }
}

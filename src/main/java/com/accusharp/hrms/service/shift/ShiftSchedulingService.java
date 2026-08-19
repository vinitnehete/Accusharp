package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.dto.BulkShiftAssignmentRequest;
import com.accusharp.hrms.dto.CopyScheduleRequest;
import com.accusharp.hrms.dto.MonthlyPlannerResponse;
import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import com.accusharp.hrms.dto.ShiftRotationRequest;
import com.accusharp.hrms.dto.ShiftScheduleResponse;
import com.accusharp.hrms.dto.ShiftSwapRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.HolidayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything that puts an employee on a shift: single and bulk assignment, the
 * monthly planner, auto-rotation, copying a previous month, holiday override
 * and swaps.
 *
 * <p>Two invariants hold throughout: one shift per employee per day (enforced
 * by the unique constraint and checked up front for a clear error), and a
 * supervisor may only schedule their own team.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftSchedulingService {

    private final ShiftScheduleRepository shiftScheduleRepository;
    private final ShiftService shiftService;
    private final EmployeeService employeeService;
    private final HolidayService holidayService;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    // ---- single assignment -------------------------------------------------

    @Transactional
    public ShiftScheduleResponse assign(ShiftAssignmentRequest request) {
        employeeService.getEntityByUserId(request.getUserId());
        assertMaySchedule(request.getAssignedBy(), request.getUserId());

        Shift shift = shiftService.getByCode(request.getShiftCode(), tenantContext.currentCompanyId().orElse(null));

        ShiftSchedule schedule = shiftScheduleRepository
                .findByUserIdAndShiftDate(request.getUserId(), request.getShiftDate())
                .orElseGet(ShiftSchedule::new);

        schedule.setUserId(request.getUserId());
        schedule.setShiftDate(request.getShiftDate());
        schedule.setShift(shift);
        schedule.setWeekOff(request.isWeekOff());
        schedule.setAssignedBy(request.getAssignedBy());

        ShiftSchedule saved = shiftScheduleRepository.save(schedule);
        log.info("shift.assign userId={} date={} shift={}", saved.getUserId(), saved.getShiftDate(),
                shift.getShiftCode());
        auditService.record("SHIFT_SCHEDULE_ASSIGN", "ShiftSchedule", saved.getUserId() + " " + saved.getShiftDate(),
                AuditOutcome.SUCCESS, "shift=" + shift.getShiftCode());
        return toResponse(saved);
    }

    // ---- bulk assignment ---------------------------------------------------

    @Transactional
    public List<ShiftScheduleResponse> assignBulk(BulkShiftAssignmentRequest request) {
        validateRange(request.getFromDate(), request.getToDate());
        // Every userId below is individually tenant-checked (assertMaySchedule -> getEntityByUserId),
        // so a single successful call can only ever span one company - the caller's own.
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        Shift shift = shiftService.getByCode(request.getShiftCode(), companyId);
        Set<LocalDate> holidays = request.isSkipHolidays()
                ? holidayService.mandatoryHolidayDates(companyId, request.getFromDate(), request.getToDate())
                : Set.of();

        List<ShiftSchedule> toSave = new ArrayList<>();
        Employee scheduler = resolveScheduler(request.getAssignedBy());

        for (String userId : request.getUserIds()) {
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(scheduler, request.getAssignedBy(), userId);

            Map<LocalDate, ShiftSchedule> existing = indexByDate(shiftScheduleRepository
                    .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                            userId, request.getFromDate(), request.getToDate()));

            for (LocalDate date = request.getFromDate(); !date.isAfter(request.getToDate());
                 date = date.plusDays(1)) {

                if (holidays.contains(date)) {
                    continue;
                }
                boolean weekOff = request.getWeekOffDays() != null
                        && request.getWeekOffDays().contains(date.getDayOfWeek());

                ShiftSchedule current = existing.get(date);
                if (current != null && !request.isOverwriteExisting()) {
                    throw new ConflictException("Employee " + userId + " already has a shift on " + date);
                }
                ShiftSchedule schedule = current != null ? current : new ShiftSchedule();
                schedule.setUserId(userId);
                schedule.setShiftDate(date);
                schedule.setShift(shift);
                schedule.setWeekOff(weekOff);
                schedule.setAssignedBy(request.getAssignedBy());
                toSave.add(schedule);
            }
        }

        log.info("shift.bulk-assign employees={} days={} shift={}", request.getUserIds().size(),
                toSave.size(), shift.getShiftCode());
        List<ShiftScheduleResponse> saved = shiftScheduleRepository.saveAll(toSave).stream()
                .map(this::toResponse).toList();
        auditService.record("SHIFT_SCHEDULE_BULK_ASSIGN", "ShiftSchedule",
                request.getFromDate() + ".." + request.getToDate(), AuditOutcome.SUCCESS,
                "employees=" + request.getUserIds().size() + " days=" + toSave.size()
                        + " shift=" + shift.getShiftCode());
        return saved;
    }

    // ---- auto rotation -----------------------------------------------------

    /**
     * Staggered rotation: employee <i>i</i> starts on cycle position <i>i</i>
     * and everyone advances one position every {@code rotationDays}, so the
     * team stays spread across the shifts instead of moving together.
     */
    @Transactional
    public List<ShiftScheduleResponse> autoRotate(ShiftRotationRequest request) {
        validateRange(request.getFromDate(), request.getToDate());

        Long companyId = tenantContext.currentCompanyId().orElse(null);
        List<Shift> cycle = request.getShiftCycle().stream()
                .map(code -> shiftService.getByCode(code, companyId)).toList();
        Set<LocalDate> holidays = request.isSkipHolidays()
                ? holidayService.mandatoryHolidayDates(companyId, request.getFromDate(), request.getToDate())
                : Set.of();

        List<ShiftSchedule> toSave = new ArrayList<>();
        Employee scheduler = resolveScheduler(request.getAssignedBy());

        for (int index = 0; index < request.getUserIds().size(); index++) {
            String userId = request.getUserIds().get(index);
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(scheduler, request.getAssignedBy(), userId);

            Map<LocalDate, ShiftSchedule> existing = indexByDate(shiftScheduleRepository
                    .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                            userId, request.getFromDate(), request.getToDate()));

            long dayOffset = 0;
            for (LocalDate date = request.getFromDate(); !date.isAfter(request.getToDate());
                 date = date.plusDays(1), dayOffset++) {

                if (holidays.contains(date)) {
                    continue;
                }
                boolean weekOff = request.getWeekOffDays() != null
                        && request.getWeekOffDays().contains(date.getDayOfWeek());

                int block = (int) (dayOffset / request.getRotationDays());
                Shift shift = cycle.get(Math.floorMod(index + block, cycle.size()));

                ShiftSchedule schedule = existing.getOrDefault(date, new ShiftSchedule());
                schedule.setUserId(userId);
                schedule.setShiftDate(date);
                schedule.setShift(shift);
                schedule.setWeekOff(weekOff);
                schedule.setAssignedBy(request.getAssignedBy());
                toSave.add(schedule);
            }
        }

        List<ShiftScheduleResponse> saved = shiftScheduleRepository.saveAll(toSave).stream()
                .map(this::toResponse).toList();
        auditService.record("SHIFT_SCHEDULE_AUTO_ROTATE", "ShiftSchedule",
                request.getFromDate() + ".." + request.getToDate(), AuditOutcome.SUCCESS,
                "employees=" + request.getUserIds().size() + " days=" + toSave.size());
        return saved;
    }

    // ---- copy previous month ----------------------------------------------

    /**
     * Copies by day-of-month. Days that do not exist in the target month (the
     * 29th to 31st of a shorter month) are simply skipped.
     */
    @Transactional
    public List<ShiftScheduleResponse> copyMonth(CopyScheduleRequest request) {
        YearMonth source = request.getSourceMonth();
        YearMonth target = request.getTargetMonth();
        if (source.equals(target)) {
            throw new BusinessRuleException("Source and target month must differ");
        }

        List<ShiftSchedule> toSave = new ArrayList<>();
        Employee scheduler = resolveScheduler(request.getAssignedBy());

        for (String userId : request.getUserIds()) {
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(scheduler, request.getAssignedBy(), userId);

            List<ShiftSchedule> sourceRoster = shiftScheduleRepository
                    .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                            userId, source.atDay(1), source.atEndOfMonth());
            Map<LocalDate, ShiftSchedule> existing = indexByDate(shiftScheduleRepository
                    .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                            userId, target.atDay(1), target.atEndOfMonth()));

            for (ShiftSchedule sourceDay : sourceRoster) {
                int dayOfMonth = sourceDay.getShiftDate().getDayOfMonth();
                if (dayOfMonth > target.lengthOfMonth()) {
                    continue;
                }
                LocalDate targetDate = target.atDay(dayOfMonth);
                ShiftSchedule current = existing.get(targetDate);
                if (current != null && !request.isOverwriteExisting()) {
                    continue;
                }
                ShiftSchedule schedule = current != null ? current : new ShiftSchedule();
                schedule.setUserId(userId);
                schedule.setShiftDate(targetDate);
                schedule.setShift(sourceDay.getShift());
                schedule.setWeekOff(sourceDay.isWeekOff());
                schedule.setAssignedBy(request.getAssignedBy());
                toSave.add(schedule);
            }
        }

        List<ShiftScheduleResponse> saved = shiftScheduleRepository.saveAll(toSave).stream()
                .map(this::toResponse).toList();
        auditService.record("SHIFT_SCHEDULE_COPY_MONTH", "ShiftSchedule", source + " -> " + target,
                AuditOutcome.SUCCESS, "employees=" + request.getUserIds().size() + " days=" + toSave.size());
        return saved;
    }

    // ---- holiday override --------------------------------------------------

    /**
     * Marks every mandatory holiday in the month as a week off across the
     * roster, so the planner reflects the holiday calendar without anyone
     * editing days by hand.
     *
     * <p>Found during a full security audit and fixed here: this previously
     * queried every company's rosters at once against every company's
     * holidays merged together, so running it as one company's HR would
     * silently rewrite every <em>other</em> company's roster too.
     * {@code ShiftSchedule} has no company column of its own (it is keyed by
     * {@code userId} - see {@code SECURITY.md}'s tenant-isolation section),
     * so scoping this means going through the already company-scoped
     * {@code EmployeeService.getActiveEntities()} to get the set of userIds
     * that are actually this caller's to touch.
     */
    @Transactional
    public int applyHolidayOverride(YearMonth month) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, month);
        if (holidays.isEmpty()) {
            return 0;
        }
        Set<String> scopedUserIds = companyId == null ? null : employeeService.getActiveEntities().stream()
                .map(Employee::getUserId)
                .collect(Collectors.toSet());

        List<ShiftSchedule> affected = shiftScheduleRepository
                .findAllByShiftDateBetween(month.atDay(1), month.atEndOfMonth()).stream()
                .filter(schedule -> scopedUserIds == null || scopedUserIds.contains(schedule.getUserId()))
                .filter(schedule -> holidays.contains(schedule.getShiftDate()))
                .filter(schedule -> !schedule.isWeekOff())
                .peek(schedule -> schedule.setWeekOff(true))
                .toList();
        shiftScheduleRepository.saveAll(affected);
        log.info("shift.holiday-override month={} updated={}", month, affected.size());
        auditService.record("SHIFT_SCHEDULE_HOLIDAY_OVERRIDE", "ShiftSchedule", month.toString(),
                AuditOutcome.SUCCESS, "updated=" + affected.size());
        return affected.size();
    }

    // ---- swap --------------------------------------------------------------

    @Transactional
    public List<ShiftScheduleResponse> swap(ShiftSwapRequest request) {
        if (request.getFirstUserId().equals(request.getSecondUserId())) {
            throw new BusinessRuleException("Cannot swap an employee with themselves");
        }
        ShiftSchedule first = requireSchedule(request.getFirstUserId(), request.getShiftDate());
        ShiftSchedule second = requireSchedule(request.getSecondUserId(), request.getShiftDate());

        assertMaySchedule(request.getAssignedBy(), first.getUserId());
        assertMaySchedule(request.getAssignedBy(), second.getUserId());

        Shift firstShift = first.getShift();
        boolean firstWeekOff = first.isWeekOff();

        first.setShift(second.getShift());
        first.setWeekOff(second.isWeekOff());
        second.setShift(firstShift);
        second.setWeekOff(firstWeekOff);

        List<ShiftScheduleResponse> saved = shiftScheduleRepository.saveAll(List.of(first, second)).stream()
                .map(this::toResponse).toList();
        auditService.record("SHIFT_SCHEDULE_SWAP", "ShiftSchedule", request.getShiftDate().toString(),
                AuditOutcome.SUCCESS, "first=" + request.getFirstUserId() + " second=" + request.getSecondUserId());
        return saved;
    }

    // ---- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ShiftScheduleResponse> getRoster(String userId, LocalDate fromDate, LocalDate toDate) {
        employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        List<ShiftSchedule> schedules = (fromDate == null || toDate == null)
                ? shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(userId)
                : shiftScheduleRepository.findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                        userId, fromDate, toDate);
        return schedules.stream().map(this::toResponse).toList();
    }

    /**
     * Calendar-shaped roster for the planner UI. {@code supervisorUserId} is
     * only a hint for ADMIN/HR - see {@link EmployeeService#plannerScope}
     * for why a SUPERVISOR/EMPLOYEE's request is silently narrowed instead
     * of trusted as-is; without that, omitting it used to hand back the
     * whole company's roster to any caller.
     */
    @Transactional(readOnly = true)
    public MonthlyPlannerResponse getMonthlyPlanner(YearMonth month, String supervisorUserId) {
        List<Employee> employees = employeeService.plannerScope(supervisorUserId);

        List<LocalDate> dates = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).toList();
        if (employees.isEmpty()) {
            return new MonthlyPlannerResponse(month, dates, List.of());
        }

        List<String> userIds = employees.stream().map(Employee::getUserId).toList();
        Map<String, Map<LocalDate, String>> byUser = new HashMap<>();
        shiftScheduleRepository.findAllByUserIdInAndShiftDateBetween(userIds, month.atDay(1), month.atEndOfMonth())
                .forEach(schedule -> byUser
                        .computeIfAbsent(schedule.getUserId(), key -> new LinkedHashMap<>())
                        .put(schedule.getShiftDate(),
                                schedule.isWeekOff() ? "WO" : schedule.getShift().getShiftCode()));

        List<MonthlyPlannerResponse.EmployeeRow> rows = employees.stream()
                .map(employee -> new MonthlyPlannerResponse.EmployeeRow(
                        employee.getUserId(),
                        employee.getEmployeeName(),
                        byUser.getOrDefault(employee.getUserId(), Map.of())))
                .toList();

        return new MonthlyPlannerResponse(month, dates, rows);
    }

    @Transactional
    public void deleteRange(String userId, LocalDate fromDate, LocalDate toDate, String assignedBy) {
        validateRange(fromDate, toDate);
        employeeService.getEntityByUserId(userId);
        assertMaySchedule(assignedBy, userId);
        shiftScheduleRepository.deleteByUserIdAndShiftDateBetween(userId, fromDate, toDate);
        auditService.record("SHIFT_SCHEDULE_DELETE_RANGE", "ShiftSchedule", userId,
                AuditOutcome.SUCCESS, "range=" + fromDate + ".." + toDate);
    }

    // ---- helpers -----------------------------------------------------------

    private ShiftSchedule requireSchedule(String userId, LocalDate date) {
        return shiftScheduleRepository.findByUserIdAndShiftDate(userId, date)
                .orElseThrow(() -> NotFoundException.of("Shift schedule", userId + " on " + date));
    }

    private Map<LocalDate, ShiftSchedule> indexByDate(List<ShiftSchedule> schedules) {
        Map<LocalDate, ShiftSchedule> index = new HashMap<>();
        schedules.forEach(schedule -> index.put(schedule.getShiftDate(), schedule));
        return index;
    }

    private void validateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }
    }

    /**
     * A supervisor may only schedule their own team. Omitting the actor (HR or
     * admin tooling) skips the check.
     */
    private void assertMaySchedule(String assignedBy, String userId) {
        assertMaySchedule(resolveScheduler(assignedBy), assignedBy, userId);
    }

    /**
     * Resolves the acting scheduler once - for {@link #assignBulk},
     * {@link #autoRotate} and {@link #copyMonth}, which otherwise re-resolved
     * the identical {@code assignedBy} employee on every iteration of their
     * per-userId loop.
     */
    private Employee resolveScheduler(String assignedBy) {
        return (assignedBy == null || assignedBy.isBlank()) ? null : employeeService.getEntityByUserId(assignedBy);
    }

    /** Same check as {@link #assertMaySchedule(String, String)}, given an already-resolved actor (or none). */
    private void assertMaySchedule(Employee actor, String assignedBy, String userId) {
        if (actor == null || assignedBy.equals(userId)) {
            return;
        }
        switch (actor.getRole()) {
            case ADMIN, HR -> { /* full roster access */ }
            case SUPERVISOR -> {
                if (!employeeService.supervises(assignedBy, userId)) {
                    throw new BusinessRuleException(
                            "Supervisor " + assignedBy + " does not manage employee " + userId);
                }
            }
            default -> throw new BusinessRuleException("Role " + actor.getRole() + " cannot assign shifts");
        }
    }

    private ShiftScheduleResponse toResponse(ShiftSchedule schedule) {
        Shift shift = schedule.getShift();
        return new ShiftScheduleResponse(schedule.getId(), schedule.getUserId(), schedule.getShiftDate(),
                shift.getShiftCode(), shift.getShiftName(), shift.getStartTime(), shift.getEndTime(),
                schedule.isWeekOff(), schedule.getAssignedBy());
    }
}

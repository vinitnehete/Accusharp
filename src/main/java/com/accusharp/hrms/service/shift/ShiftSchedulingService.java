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
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
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

    // ---- single assignment -------------------------------------------------

    @Transactional
    public ShiftScheduleResponse assign(ShiftAssignmentRequest request) {
        employeeService.getEntityByUserId(request.getUserId());
        assertMaySchedule(request.getAssignedBy(), request.getUserId());

        Shift shift = shiftService.getByCode(request.getShiftCode());

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
        return toResponse(saved);
    }

    // ---- bulk assignment ---------------------------------------------------

    @Transactional
    public List<ShiftScheduleResponse> assignBulk(BulkShiftAssignmentRequest request) {
        validateRange(request.getFromDate(), request.getToDate());
        Shift shift = shiftService.getByCode(request.getShiftCode());
        Set<LocalDate> holidays = request.isSkipHolidays()
                ? holidayService.mandatoryHolidayDates(request.getFromDate(), request.getToDate())
                : Set.of();

        List<ShiftSchedule> toSave = new ArrayList<>();

        for (String userId : request.getUserIds()) {
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(request.getAssignedBy(), userId);

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
        return shiftScheduleRepository.saveAll(toSave).stream().map(this::toResponse).toList();
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

        List<Shift> cycle = request.getShiftCycle().stream().map(shiftService::getByCode).toList();
        Set<LocalDate> holidays = request.isSkipHolidays()
                ? holidayService.mandatoryHolidayDates(request.getFromDate(), request.getToDate())
                : Set.of();

        List<ShiftSchedule> toSave = new ArrayList<>();

        for (int index = 0; index < request.getUserIds().size(); index++) {
            String userId = request.getUserIds().get(index);
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(request.getAssignedBy(), userId);

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

        return shiftScheduleRepository.saveAll(toSave).stream().map(this::toResponse).toList();
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

        for (String userId : request.getUserIds()) {
            employeeService.getEntityByUserId(userId);
            assertMaySchedule(request.getAssignedBy(), userId);

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

        return shiftScheduleRepository.saveAll(toSave).stream().map(this::toResponse).toList();
    }

    // ---- holiday override --------------------------------------------------

    /**
     * Marks every mandatory holiday in the month as a week off across the
     * roster, so the planner reflects the holiday calendar without anyone
     * editing days by hand.
     */
    @Transactional
    public int applyHolidayOverride(YearMonth month) {
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(month);
        if (holidays.isEmpty()) {
            return 0;
        }
        List<ShiftSchedule> affected = shiftScheduleRepository
                .findAllByShiftDateBetween(month.atDay(1), month.atEndOfMonth()).stream()
                .filter(schedule -> holidays.contains(schedule.getShiftDate()))
                .filter(schedule -> !schedule.isWeekOff())
                .peek(schedule -> schedule.setWeekOff(true))
                .toList();
        shiftScheduleRepository.saveAll(affected);
        log.info("shift.holiday-override month={} updated={}", month, affected.size());
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

        return shiftScheduleRepository.saveAll(List.of(first, second)).stream().map(this::toResponse).toList();
    }

    // ---- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ShiftScheduleResponse> getRoster(String userId, LocalDate fromDate, LocalDate toDate) {
        employeeService.getEntityByUserId(userId);
        List<ShiftSchedule> schedules = (fromDate == null || toDate == null)
                ? shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(userId)
                : shiftScheduleRepository.findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                        userId, fromDate, toDate);
        return schedules.stream().map(this::toResponse).toList();
    }

    /** Calendar-shaped roster for the planner UI. */
    @Transactional(readOnly = true)
    public MonthlyPlannerResponse getMonthlyPlanner(YearMonth month, String supervisorUserId) {
        List<Employee> employees = supervisorUserId == null
                ? employeeService.getActiveEntities()
                : employeeService.getActiveEntities().stream()
                        .filter(e -> e.getSupervisor() != null
                                && supervisorUserId.equals(e.getSupervisor().getUserId()))
                        .toList();

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
    public void deleteRange(String userId, LocalDate fromDate, LocalDate toDate) {
        validateRange(fromDate, toDate);
        shiftScheduleRepository.deleteByUserIdAndShiftDateBetween(userId, fromDate, toDate);
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
        if (assignedBy == null || assignedBy.isBlank() || assignedBy.equals(userId)) {
            return;
        }
        Employee actor = employeeService.getEntityByUserId(assignedBy);
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

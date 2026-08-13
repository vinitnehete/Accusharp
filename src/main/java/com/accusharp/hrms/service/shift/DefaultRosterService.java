package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps every active {@link EmployeeStatus#PERMANENT} employee rostered onto
 * the {@code GENERAL} shift without HR ever having to assign it by hand.
 *
 * <p>Only ever <b>fills gaps</b>: a day that already has a roster row - hand
 * edited, swapped, part of a holiday override, anything - is never touched.
 * The single-day {@code PUT}/{@code POST} on {@code ShiftScheduleController}
 * still works exactly as before, so HR can override any individual day.
 *
 * <p>Deliberately independent of {@code EmployeeService} (which {@link
 * ShiftSchedulingService} already depends on) so that {@code EmployeeService}
 * can call this right after creating a permanent employee without forming a
 * circular bean dependency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultRosterService {

    /** The only employment type this default applies to - see EmployeeStatus's Javadoc. */
    private static final EmployeeStatus DEFAULT_ROSTER_STATUS = EmployeeStatus.PERMANENT;
    private static final String DEFAULT_SHIFT_CODE = "GENERAL";
    private static final Set<DayOfWeek> DEFAULT_WEEK_OFF = Set.of(DayOfWeek.SUNDAY);
    private static final String ASSIGNED_BY_SYSTEM = "SYSTEM_DEFAULT_ROSTER";

    /** How far ahead coverage is kept topped up, so the roster never runs dry between runs. */
    private static final int BUFFER_MONTHS_AHEAD = 2;

    private final EmployeeRepository employeeRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final ShiftService shiftService;
    private final AuditService auditService;

    /**
     * Runs once a month and re-tops-up every active permanent employee's
     * roster out to {@link #BUFFER_MONTHS_AHEAD} months ahead. Idempotent -
     * a missed or re-run execution only ever adds the days still missing.
     */
    @Scheduled(cron = "0 0 2 1 * ?")
    public void runMonthly() {
        LocalDate from = LocalDate.now();
        LocalDate to = defaultToDate(from);
        int created = ensureForAllPermanentEmployees(from, to);
        log.info("shift.default-roster.scheduled range={}..{} created={}", from, to, created);
    }

    /** Every active permanent employee, across every company. */
    @Transactional
    public int ensureForAllPermanentEmployees(LocalDate fromDate, LocalDate toDate) {
        List<Employee> employees = employeeRepository
                .findByRecordStatusAndStatus(RecordStatus.ACTIVE, DEFAULT_ROSTER_STATUS);
        int created = ensure(employees, fromDate, toDate);

        Map<Long, Integer> countByCompany = new HashMap<>();
        for (Employee employee : employees) {
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            countByCompany.merge(companyId, 1, Integer::sum);
        }
        countByCompany.forEach((companyId, count) -> auditService.recordWithActor(
                "system", null, companyId,
                "SHIFT_SCHEDULE_DEFAULT_ROSTER", "ShiftSchedule", fromDate + ".." + toDate,
                AuditOutcome.SUCCESS, "employees=" + count));
        return created;
    }

    /** One newly created (or just-made-permanent) employee - called from {@code EmployeeService}. */
    @Transactional
    public int ensureForEmployee(Employee employee) {
        if (employee.getStatus() != DEFAULT_ROSTER_STATUS
                || employee.getRecordStatus() != RecordStatus.ACTIVE) {
            return 0;
        }
        LocalDate from = employee.getJoiningDate() != null && employee.getJoiningDate().isAfter(LocalDate.now())
                ? employee.getJoiningDate()
                : LocalDate.now();
        return ensure(List.of(employee), from, defaultToDate(from));
    }

    /**
     * Best-effort: this is a convenience default, never a precondition for
     * creating or updating an employee. A missing {@code GENERAL} shift (an
     * unseeded environment, or a company that renamed/removed it) logs a
     * warning and skips that employee rather than failing the write that
     * triggered this. Uses {@link ShiftService#findByCodeIfPresent} rather
     * than {@link ShiftService#getByCode} deliberately - see that method's
     * Javadoc: a caught exception here would already be too late to stop
     * this participating transaction being marked rollback-only.
     */
    private int ensure(List<Employee> employees, LocalDate fromDate, LocalDate toDate) {
        int created = 0;
        for (Employee employee : employees) {
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            Optional<Shift> shiftLookup = shiftService.findByCodeIfPresent(DEFAULT_SHIFT_CODE, companyId);
            if (shiftLookup.isEmpty()) {
                log.warn("shift.default-roster.skip userId={} reason=no {} shift for companyId={}",
                        employee.getUserId(), DEFAULT_SHIFT_CODE, companyId);
                continue;
            }
            Shift shift = shiftLookup.get();

            Set<LocalDate> existingDates = shiftScheduleRepository
                    .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(employee.getUserId(), fromDate, toDate)
                    .stream().map(ShiftSchedule::getShiftDate).collect(Collectors.toSet());

            List<ShiftSchedule> toSave = new ArrayList<>();
            for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
                if (existingDates.contains(date)) {
                    continue;
                }
                ShiftSchedule schedule = new ShiftSchedule();
                schedule.setUserId(employee.getUserId());
                schedule.setShiftDate(date);
                schedule.setShift(shift);
                schedule.setWeekOff(DEFAULT_WEEK_OFF.contains(date.getDayOfWeek()));
                schedule.setAssignedBy(ASSIGNED_BY_SYSTEM);
                toSave.add(schedule);
            }
            shiftScheduleRepository.saveAll(toSave);
            created += toSave.size();
        }
        return created;
    }

    private LocalDate defaultToDate(LocalDate from) {
        return YearMonth.from(from).plusMonths(BUFFER_MONTHS_AHEAD).atEndOfMonth();
    }
}

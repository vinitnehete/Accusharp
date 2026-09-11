package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The roster an employee has without anybody writing one down.
 *
 * <p>An employee who always works the same shift has a roster that is fully
 * determined by two facts already on their employee record: the shift itself,
 * and which days of the week they do not work. This resolver derives their days
 * from those two facts at read time, and hands back the same
 * {@link ShiftSchedule} shape every existing caller already consumes.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code DefaultRosterService} wrote those days into the table instead - a
 * cron job that ran on the first of each month and generated roughly ninety
 * rows per permanent employee per quarter, whose entire content was "GENERAL,
 * and Sunday is off". Three things were wrong with that, and all three were
 * costing money:
 *
 * <ul>
 *   <li><b>The rows ran out.</b> Coverage extended two months ahead of whenever
 *       the job last ran. Past that horizon an employee had no roster row, so
 *       attendance generation had no shift to measure their punches against and
 *       wrote them down as {@code ABSENT} - with their real punch times sitting
 *       in the same row, contradicting the status. Every one of those days was
 *       loss of pay for somebody who had come to work.</li>
 *   <li><b>Sunday was hardcoded.</b> Every employee got Sunday whether or not
 *       that was their day, and there was nowhere to say otherwise.</li>
 *   <li><b>The rows went stale.</b> Changing an employee's weekly off did
 *       nothing to the ninety rows already written for them.</li>
 * </ul>
 *
 * <p>Deriving instead of storing fixes all three at once: the days cannot run
 * out, they read the employee's own configured week-off, and changing that
 * changes every day that has not been explicitly overridden, at once.
 *
 * <h2>Two rules</h2>
 *
 * <p><b>Explicit always wins.</b> A row somebody assigned - by hand, in a bulk
 * import, through a swap or a holiday override - is returned exactly as stored.
 * The default only ever fills a date that has no row at all, so every roster
 * endpoint keeps working unchanged and HR keeps being able to put a permanent
 * employee on a night shift for one day.
 *
 * <p><b>Nothing is written.</b> The days this produces are transient and
 * flagged {@link ShiftSchedule#isDefaulted()}. They must never be passed to a
 * repository save.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultRosterResolver {

    /** The shift an auto-rostered employee is assumed to work every day. */
    private static final String DEFAULT_SHIFT_CODE = "GENERAL";

    private final ShiftService shiftService;

    /**
     * This employee's roster for the range: their explicit rows, plus a derived
     * day for every date in between that has none.
     *
     * <p>Returns {@code explicit} untouched - not a copy with defaults added -
     * for an employee this does not apply to. Contract and day-wise staff are
     * rostered sparsely on purpose: for them an unrostered day genuinely means
     * "not a working day", and filling it would invent loss of pay.
     *
     * @param explicit this employee's stored rows overlapping the range, in any
     *                 order
     * @return ascending by {@code shiftDate}, which is what
     *         {@code AttendanceWindowResolver.windowsFor} requires
     */
    @Transactional(readOnly = true)
    public List<ShiftSchedule> merge(Employee employee, List<ShiftSchedule> explicit,
                                     LocalDate fromDate, LocalDate toDate) {
        if (!employee.autoRostersDefaultShift()) {
            return sorted(explicit);
        }

        Optional<Shift> defaultShift = resolveDefaultShift(employee);
        if (defaultShift.isEmpty()) {
            return sorted(explicit);
        }

        Map<LocalDate, ShiftSchedule> byDate = new HashMap<>();
        explicit.forEach(schedule -> byDate.put(schedule.getShiftDate(), schedule));

        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (byDate.containsKey(date) || !isEmployedOn(employee, date)) {
                continue;
            }
            byDate.put(date, defaultDay(employee, date, defaultShift.get()));
        }
        return sorted(byDate.values());
    }

    /**
     * One derived day. Transient, flagged, and with no {@code assignedBy}:
     * nobody assigned it.
     */
    private ShiftSchedule defaultDay(Employee employee, LocalDate date, Shift shift) {
        ShiftSchedule schedule = new ShiftSchedule();
        schedule.setUserId(employee.getUserId());
        schedule.setShiftDate(date);
        schedule.setShift(shift);
        schedule.setWeekOff(employee.isWeekOffOn(date));
        schedule.setDefaulted(true);
        return schedule;
    }

    /**
     * Best-effort, exactly like the generator this replaces: a company with no
     * {@code GENERAL} shift - an unseeded environment, or one that renamed it -
     * logs a warning and gets no defaults, rather than failing the attendance
     * run that asked. Those employees fall back to the unrostered handling that
     * was already there.
     */
    private Optional<Shift> resolveDefaultShift(Employee employee) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        Optional<Shift> shift = shiftService.findByCodeIfPresent(DEFAULT_SHIFT_CODE, companyId);
        if (shift.isEmpty()) {
            log.warn("roster.default.skip userId={} reason=no {} shift for companyId={}",
                    employee.getUserId(), DEFAULT_SHIFT_CODE, companyId);
        }
        return shift;
    }

    /**
     * Nobody works before they are hired or after they leave. Payroll already
     * caps payable days by the same window, so filling outside it would
     * manufacture loss of pay for days the employee was not on the books.
     */
    private boolean isEmployedOn(Employee employee, LocalDate date) {
        LocalDate joined = employee.getJoiningDate();
        LocalDate relieved = employee.getRelievingDate();
        return (joined == null || !date.isBefore(joined))
                && (relieved == null || !date.isAfter(relieved));
    }

    private List<ShiftSchedule> sorted(java.util.Collection<ShiftSchedule> schedules) {
        return schedules.stream()
                .sorted(Comparator.comparing(ShiftSchedule::getShiftDate))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}

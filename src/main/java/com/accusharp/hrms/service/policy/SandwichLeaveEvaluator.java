package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.service.leave.LeaveCalculationService;
import com.accusharp.hrms.service.leave.LeaveCalculationService.LeaveDay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The {@code SANDWICH_LEAVE} rule: a mandatory holiday is paid only to someone
 * who worked the working day just before it or just after it.
 *
 * <p>Weekly offs next to the holiday are looked past, never charged - the days
 * that decide are the nearest working days on each side. When neither was
 * worked (leave or absence on both sides) the holiday is lost, and, if the rule
 * says so, the paid leave on those two days too. Leave applied <em>through</em>
 * the holiday keeps it paid: that is the proper way to take the long weekend.
 *
 * <p>A day with no stored row is unknown, and unknown is never charged - a
 * neighbouring month not generated yet, a joiner's first day, a sparse roster.
 * Each lost day is charged in its own month.
 */
@Component
@RequiredArgsConstructor
public class SandwichLeaveEvaluator {

    /** How far past the month the far side of a holiday is looked for. */
    private static final int LOOK_AROUND_DAYS = 10;

    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final LeaveCalculationService leaveCalculationService;

    /** One stored day, reduced to what the rule reads. */
    public record Day(LocalDate date, boolean workingDay, boolean holiday, boolean worked, BigDecimal paidLeave) {

        /**
         * A holiday on a weekly off took no working day away, so it reads as a
         * weekly off. A lone punch reads as worked: it is evidence the person
         * came in, and invariant 5 says it is never an absence.
         */
        public static Day of(DailyAttendance day, LeaveDay leave) {
            return new Day(day.getAttendanceDate(), day.isWorkingDay(), day.isHoliday() && !day.isWeekOff(),
                    day.getStatus().dayFraction().signum() > 0 || day.isInvalidPunch(),
                    leave != null && leave.paid() ? leave.fraction() : BigDecimal.ZERO);
        }
    }

    /** What the rule takes away inside one month. */
    public record Charge(List<LocalDate> holidays, List<LocalDate> leaveDates, BigDecimal leaveDays) {

        public static final Charge NONE = new Charge(List.of(), List.of(), BigDecimal.ZERO);

        public BigDecimal holidayDays() {
            return BigDecimal.valueOf(holidays.size());
        }

        public BigDecimal lopDays() {
            return holidayDays().add(leaveDays);
        }
    }

    /** The month's charge from the stored days, reading a little past each end of it. */
    public Charge charge(String userId, YearMonth month, boolean adjacentLeaveUnpaid) {
        LocalDate from = month.atDay(1).minusDays(LOOK_AROUND_DAYS);
        LocalDate to = month.atEndOfMonth().plusDays(LOOK_AROUND_DAYS);
        Map<LocalDate, LeaveDay> leave = leaveCalculationService.approvedLeaveDaysBetween(userId, from, to);
        return charge(dailyAttendanceRepository
                        .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, from, to).stream()
                        .map(day -> Day.of(day, leave.get(day.getAttendanceDate())))
                        .toList(),
                month, adjacentLeaveUnpaid);
    }

    /** The rule itself: a pure function of the days. */
    public static Charge charge(List<Day> days, YearMonth month, boolean adjacentLeaveUnpaid) {
        NavigableMap<LocalDate, Day> byDate = new TreeMap<>();
        days.forEach(day -> byDate.put(day.date(), day));

        List<LocalDate> holidays = new ArrayList<>();
        // Keyed by date: a leave day between two holidays is charged once.
        NavigableMap<LocalDate, BigDecimal> leave = new TreeMap<>();

        for (Day before : byDate.values()) {
            if (!before.workingDay()) {
                continue;
            }
            // The unbroken run of days off that follows, and the working day after it.
            List<Day> daysOff = new ArrayList<>();
            Day after = byDate.get(before.date().plusDays(1));
            while (after != null && !after.workingDay()) {
                daysOff.add(after);
                after = byDate.get(after.date().plusDays(1));
            }
            if (after == null || before.worked() || after.worked() || daysOff.stream().anyMatch(Day::worked)) {
                continue;
            }
            List<LocalDate> lost = daysOff.stream()
                    .filter(day -> day.holiday() && day.paidLeave().signum() == 0)
                    .map(Day::date)
                    .toList();
            if (lost.isEmpty()) {
                continue;
            }
            lost.stream().filter(date -> YearMonth.from(date).equals(month)).forEach(holidays::add);
            if (adjacentLeaveUnpaid) {
                Stream.of(before, after)
                        .filter(day -> YearMonth.from(day.date()).equals(month) && day.paidLeave().signum() > 0)
                        .forEach(day -> leave.put(day.date(), day.paidLeave()));
            }
        }

        if (holidays.isEmpty() && leave.isEmpty()) {
            return Charge.NONE;
        }
        return new Charge(List.copyOf(holidays), List.copyOf(leave.keySet()),
                leave.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}

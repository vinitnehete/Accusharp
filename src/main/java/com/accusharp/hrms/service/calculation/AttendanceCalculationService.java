package com.accusharp.hrms.service.calculation;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Turns raw punches into one day of attendance, interpreted through the shift
 * the employee was actually scheduled on.
 *
 * <p><b>Only the first and last punch of a day decide it.</b> Anything punched
 * in between is recorded in {@code device_logs} but has no effect on hours,
 * break, overtime or status, and the unpaid break is always the shift's
 * configured {@code breakMinutes}.
 *
 * <p>An earlier version tried to be cleverer: with four or more punches and an
 * even count it treated the middle pairs as real out-and-back-in cycles and
 * measured the break between them. The device makes that unsafe - a punch row
 * is just {@code (user_id, log_date)} with no in/out flag, so nothing
 * distinguishes a genuine mid-shift exit from the reader firing twice on one
 * badge. In production it fired twice routinely, seconds apart, and the
 * consequence was severe: punches at 10:25:42, 10:25:44, 20:40:13 and 20:40:14
 * were read as one minute of work, a ten-hour break, and one more minute -
 * {@code 614 - 614 = 0} worked minutes, so a full day plus two hours of
 * overtime scored {@code ABSENT} and became loss of pay. A rule that turns the
 * most common hardware quirk into an unpaid day is not worth the accuracy it
 * buys on the rare real mid-shift exit, which is what a manual correction is
 * for.
 *
 * <p>The one piece of genuinely non-obvious logic lives here: a night shift's
 * punch window spans into the next calendar day, but the day still belongs to
 * the shift's start date. Keeping that in a single method is why callers never
 * have to think about midnight.
 */
@Service
public class AttendanceCalculationService {

    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Start of the window in which a punch counts towards this shift day.
     * People badge in a little before the hour, so the window opens early by
     * {@code rule.entryWindowBufferMinutes} - not symmetric on the closing
     * side, which uses the shift's own overtime window instead (see {@link
     * #windowEnd}), because someone who stays hours late has worked overtime
     * and must not be read as having never punched out.
     */
    public LocalDateTime windowStart(LocalDate shiftDate, Shift shift, AttendanceRule rule) {
        return shiftDate.atTime(shift.getStartTime()).minusMinutes(rule.getEntryWindowBufferMinutes());
    }

    /**
     * End of the punch window, exclusive. For a shift that crosses midnight
     * this lands on the following calendar day.
     */
    public LocalDateTime windowEnd(LocalDate shiftDate, Shift shift) {
        return scheduledEnd(shiftDate, shift).plusMinutes(shift.getOvertimeWindowMinutes());
    }

    /** Scheduled end of the shift itself (no buffer) - used for early exit. */
    public LocalDateTime scheduledEnd(LocalDate shiftDate, Shift shift) {
        LocalDate endDate = shift.crossesMidnight() ? shiftDate.plusDays(1) : shiftDate;
        return endDate.atTime(shift.getEndTime());
    }

    /**
     * @param punches every punch inside the shift window, ascending by time
     */
    public DailyAttendanceResponse calculateDay(String userId, LocalDate shiftDate, Shift shift,
                                                List<DeviceLog> punches, boolean weekOff,
                                                boolean holiday, boolean onLeave, AttendanceRule rule) {

        if (punches.size() < 2) {
            AttendanceStatus status = resolveNonWorkingStatus(punches.size(), weekOff, holiday, onLeave);
            return new DailyAttendanceResponse(userId, shiftDate, shift.getShiftCode(),
                    punches.isEmpty() ? null : punches.getFirst().getLogDate(), null,
                    zero(), zero(), zero(), 0, 0,
                    status == AttendanceStatus.INVALID_PUNCH, status);
        }

        LocalDateTime firstIn = punches.getFirst().getLogDate();
        LocalDateTime lastOut = punches.getLast().getLogDate();

        long spanMinutes = Duration.between(firstIn, lastOut).toMinutes();
        // The first and last punch decide the day; everything between them is
        // ignored. The break is always the shift's configured one.
        long breakMinutes = shift.getBreakMinutes();
        long workedMinutes = Math.max(0, spanMinutes - breakMinutes);

        LocalDateTime graceEnd = shiftDate.atTime(shift.getStartTime()).plusMinutes(shift.getGraceMinutes());
        int lateMinutes = (int) Math.max(0, Duration.between(graceEnd, firstIn).toMinutes());

        LocalDateTime scheduledEnd = scheduledEnd(shiftDate, shift);
        int earlyExitMinutes = (int) Math.max(0, Duration.between(lastOut, scheduledEnd).toMinutes());

        long shiftMinutes = (long) shift.getWorkingHours() * 60;
        long overtimeMinutes = Math.max(0, workedMinutes - shiftMinutes);

        AttendanceStatus status = resolveWorkedStatus(workedMinutes, shiftMinutes, weekOff, holiday, rule);

        return new DailyAttendanceResponse(userId, shiftDate, shift.getShiftCode(), firstIn, lastOut,
                toHours(workedMinutes), toHours(breakMinutes), toHours(overtimeMinutes),
                lateMinutes, earlyExitMinutes, false, status);
    }

    /** How much of a day this attendance status is worth for payroll. */
    public BigDecimal dayFraction(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> BigDecimal.ONE;
            case HALF_DAY -> new BigDecimal("0.5");
            default -> BigDecimal.ZERO;
        };
    }

    private AttendanceStatus resolveNonWorkingStatus(int punchCount, boolean weekOff,
                                                     boolean holiday, boolean onLeave) {
        if (punchCount == 1) {
            // A lone punch is a device or user error, not an absence.
            return AttendanceStatus.INVALID_PUNCH;
        }
        if (onLeave) {
            return AttendanceStatus.ON_LEAVE;
        }
        if (holiday) {
            return AttendanceStatus.HOLIDAY;
        }
        if (weekOff) {
            return AttendanceStatus.WEEKLY_OFF;
        }
        return AttendanceStatus.ABSENT;
    }

    private AttendanceStatus resolveWorkedStatus(long workedMinutes, long shiftMinutes,
                                                 boolean weekOff, boolean holiday, AttendanceRule rule) {
        if (weekOff || holiday) {
            // Worked on a day off - still present, and the hours count as overtime.
            return AttendanceStatus.PRESENT;
        }
        BigDecimal worked = BigDecimal.valueOf(workedMinutes);
        BigDecimal expected = BigDecimal.valueOf(Math.max(shiftMinutes, 1));

        BigDecimal fullDayThreshold = rule.getFullDayThresholdPercent().divide(HUNDRED, 4, RoundingMode.HALF_UP);
        BigDecimal halfDayThreshold = rule.getHalfDayThresholdPercent().divide(HUNDRED, 4, RoundingMode.HALF_UP);

        if (worked.compareTo(expected.multiply(fullDayThreshold)) >= 0) {
            return AttendanceStatus.PRESENT;
        }
        if (worked.compareTo(expected.multiply(halfDayThreshold)) >= 0) {
            return AttendanceStatus.HALF_DAY;
        }
        return AttendanceStatus.ABSENT;
    }

    private BigDecimal toHours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}

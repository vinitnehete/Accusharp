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
        long breakMinutes = resolveBreakMinutes(punches, shift);
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

    /**
     * With four or more punches the middle pairs are real in/out cycles, so
     * the break is the actual time spent outside. Otherwise fall back to the
     * shift's configured unpaid break.
     */
    private long resolveBreakMinutes(List<DeviceLog> punches, Shift shift) {
        if (punches.size() < 4 || punches.size() % 2 != 0) {
            return shift.getBreakMinutes();
        }
        long breakMinutes = 0;
        for (int i = 1; i < punches.size() - 1; i += 2) {
            breakMinutes += Duration.between(punches.get(i).getLogDate(),
                    punches.get(i + 1).getLogDate()).toMinutes();
        }
        return Math.max(0, breakMinutes);
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

package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.Shift;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A shift plus the two things about it that are <em>derived</em> rather than
 * stored, and that therefore used to be invisible to whoever configures it.
 *
 * <p>Whether a shift crosses midnight is not a flag anyone sets - it is inferred
 * from the times. A shift named NIGHT that is stored as 06:00-19:00 is simply a
 * day shift with a misleading name, and the engine has no way to know
 * otherwise. Returning {@code crossesMidnight} makes that immediately obvious
 * instead of surfacing weeks later as wrong attendance.
 *
 * <p>{@code warnings} flags configuration that is legal but will produce
 * attendance nobody wants.
 */
public record ShiftResponse(
        Long id,
        String shiftCode,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        int workingHours,
        int breakMinutes,
        int graceMinutes,
        int overtimeWindowMinutes,
        boolean crossesMidnight,
        BigDecimal spanHours,
        List<String> warnings
) {

    public static ShiftResponse of(Shift shift) {
        return new ShiftResponse(shift.getId(), shift.getShiftCode(), shift.getShiftName(),
                shift.getStartTime(), shift.getEndTime(), shift.getWorkingHours(),
                shift.getBreakMinutes(), shift.getGraceMinutes(), shift.getOvertimeWindowMinutes(),
                shift.crossesMidnight(), spanHours(shift), warningsFor(shift));
    }

    private static BigDecimal spanHours(Shift shift) {
        return BigDecimal.valueOf(shift.span().toMinutes())
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
    }

    /** Legal but almost certainly wrong. */
    public static List<String> warningsFor(Shift shift) {
        List<String> warnings = new ArrayList<>();

        if (shift.getOvertimeWindowMinutes() == 0) {
            warnings.add("overtimeWindowMinutes is 0: the punch window closes at the exact "
                    + "scheduled end, so any exit punched even a minute late is discarded and "
                    + "the day becomes an invalid punch. Set it to cover your longest overrun.");
        }

        long paidMinutes = (long) shift.getWorkingHours() * 60 + shift.getBreakMinutes();
        long spanMinutes = shift.span().toMinutes();
        if (spanMinutes - paidMinutes >= 60) {
            BigDecimal daily = BigDecimal.valueOf(spanMinutes - paidMinutes)
                    .divide(new BigDecimal("60"), 1, RoundingMode.HALF_UP);
            warnings.add("the shift spans " + spanHours(shift) + "h but only "
                    + shift.getWorkingHours() + "h are paid plus " + shift.getBreakMinutes()
                    + " break minutes, so anyone working the full shift books about " + daily
                    + "h of overtime every day. Check workingHours and breakMinutes.");
        }

        return warnings;
    }
}

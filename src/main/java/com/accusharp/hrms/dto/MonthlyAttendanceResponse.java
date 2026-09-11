package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.AttendancePolicyOutcome;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public record MonthlyAttendanceResponse(
        String userId,
        String employeeName,
        YearMonth month,
        long workingDays,
        BigDecimal presentDays,
        BigDecimal absentDays,
        long halfDays,
        BigDecimal leaveDays,
        long holidayDays,
        long weekOffDays,
        long lateCount,
        long earlyExitCount,
        long invalidPunches,
        BigDecimal totalHours,
        BigDecimal overtimeHours,
        BigDecimal lopDays,

        /**
         * The share of {@link #lopDays} that came from month-scoped policy rules
         * rather than the working-days arithmetic. Zero for every company that
         * configures no rules.
         */
        BigDecimal policyLopDays,

        /** Compensatory-off days earned by working a weekly off or holiday. */
        BigDecimal compOffCreditDays,

        /**
         * Days a {@code DAY_OFF_WORK = PAID_DAY} rule credited for working a day
         * off - already inside {@link #presentDays}, listed separately so the
         * extra paid day can be explained.
         */
        BigDecimal paidDayOffDays,

        /** Days a weekly off was worked, whether by roster or on the employee's own configured day. */
        long weekOffWorkedDays,

        /** Days somebody punched on their weekly off with no shift assigned. */
        long weekOffUnrosteredPunchDays,

        /** One row per month-scoped rule that produced a penalty, each with the numbers it used. */
        List<AttendancePolicyOutcome> policyOutcomes,

        List<DailyAttendanceResponse> days
) {
}

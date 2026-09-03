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

        /** One row per month-scoped rule that produced a penalty, each with the numbers it used. */
        List<AttendancePolicyOutcome> policyOutcomes,

        List<DailyAttendanceResponse> days
) {
}

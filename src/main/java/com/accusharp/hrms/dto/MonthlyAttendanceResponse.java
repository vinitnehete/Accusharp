package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.AttendancePolicyOutcome;
import com.accusharp.hrms.service.policy.SandwichLeaveEvaluator;

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

        /**
         * The holidays a sandwich rule took away and the paid leave it made
         * unpaid, by date - already inside {@link #lopDays}. Lets the records
         * screen mark those days, whose own status does not change.
         */
        SandwichLeaveEvaluator.Charge sandwich,

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

        /**
         * False when the employee's work policy leaves attendance untracked - a
         * director paid a fixed salary punches nothing, so the absent days below
         * are not a finding and must not be shown to them as one. Always true in
         * the roll-up payroll reads; only the read endpoint sets it, so a
         * generation run does not resolve a policy per employee for nothing.
         */
        boolean attendanceTracked,

        List<DailyAttendanceResponse> days
) {

    public MonthlyAttendanceResponse withAttendanceTracked(boolean tracked) {
        return new MonthlyAttendanceResponse(userId, employeeName, month, workingDays, presentDays, absentDays,
                halfDays, leaveDays, holidayDays, weekOffDays, lateCount, earlyExitCount, invalidPunches,
                totalHours, overtimeHours, lopDays, policyLopDays, sandwich, compOffCreditDays, paidDayOffDays,
                weekOffWorkedDays, weekOffUnrosteredPunchDays, policyOutcomes, tracked, days);
    }
}

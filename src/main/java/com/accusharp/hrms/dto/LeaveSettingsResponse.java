package com.accusharp.hrms.dto;

/**
 * A company's leave settings.
 *
 * @param leaveYearStartMonth   1 (January, calendar year) or 4 (April, financial year)
 * @param canChange             whether the leave year can still be switched
 * @param currentLeaveYear      the leave year today falls in, numbered by the year it starts
 * @param currentLeaveYearLabel {@code 2026}, or {@code 2026-27} for a financial year
 * @param blockedBecause        why it cannot be switched, when it cannot; otherwise null
 */
public record LeaveSettingsResponse(
        int leaveYearStartMonth,
        boolean canChange,
        int currentLeaveYear,
        String currentLeaveYearLabel,
        String blockedBecause
) {
}

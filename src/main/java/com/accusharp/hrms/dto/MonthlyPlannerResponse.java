package com.accusharp.hrms.dto;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Calendar-shaped view for the planner UI: one row per employee, one column
 * per date, holding the shift code (or {@code null} where nothing is planned).
 */
public record MonthlyPlannerResponse(
        YearMonth month,
        List<LocalDate> dates,
        List<EmployeeRow> rows
) {

    /**
     * @param shiftByDate     the shift code for each planned date, or {@code WO}
     *                        for a weekly off
     * @param defaultedByDate which of those days nobody assigned - derived from
     *                        the employee's fixed shift and configured weekly
     *                        off rather than stored (see
     *                        {@code DefaultRosterResolver}). Keyed identically
     *                        to {@code shiftByDate}, and additive: a client that
     *                        ignores it renders exactly what it rendered before.
     *                        The planner is where HR decides what still needs
     *                        assigning, so "this is just the usual shift" and
     *                        "somebody chose this" have to look different.
     */
    public record EmployeeRow(String userId, String employeeName,
                              Map<LocalDate, String> shiftByDate,
                              Map<LocalDate, Boolean> defaultedByDate) {
    }
}

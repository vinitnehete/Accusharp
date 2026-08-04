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

    public record EmployeeRow(String userId, String employeeName, Map<LocalDate, String> shiftByDate) {
    }
}

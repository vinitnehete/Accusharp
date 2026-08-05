package com.accusharp.hrms.dto;

import java.time.YearMonth;
import java.util.List;

/**
 * What a generation run actually did. {@code manualPreserved} and
 * {@code lockedSkipped} are reported rather than hidden, so an admin can see at
 * a glance that a rerun did not quietly undo their corrections.
 */
public record AttendanceGenerationResponse(
        YearMonth month,
        int employeesProcessed,
        int daysGenerated,
        int manualPreserved,
        int lockedSkipped,
        List<String> employeesWithoutRoster
) {
}

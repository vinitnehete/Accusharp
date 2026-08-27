package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * What a generation run actually did. {@code manualPreserved} and
 * {@code lockedSkipped} are reported rather than hidden, so an admin can see at
 * a glance that a rerun did not quietly undo their corrections.
 *
 * <p>{@code changes} is populated only on a dry run - the point of a dry run is
 * to see which days a real run would move, and by how much, before it moves
 * them. A real run reports counts; the stored rows are its record.
 */
public record AttendanceGenerationResponse(
        YearMonth month,
        int employeesProcessed,
        int daysGenerated,
        int manualPreserved,
        int lockedSkipped,
        List<String> employeesWithoutRoster,
        boolean dryRun,
        List<DayChange> changes
) {

    /**
     * One day a run would change, before and after. A day whose status and
     * punch times are unchanged is not reported - only the differences matter.
     */
    public record DayChange(
            String userId,
            LocalDate date,
            String shiftCode,
            AttendanceStatus previousStatus,
            AttendanceStatus newStatus,
            LocalDateTime previousFirstIn,
            LocalDateTime newFirstIn,
            LocalDateTime previousLastOut,
            LocalDateTime newLastOut
    ) {
    }
}

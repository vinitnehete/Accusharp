package com.accusharp.hrms.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a bulk attendance upload.
 *
 * <p>Reports skipped days as prominently as applied ones, and that is the
 * point of the shape: a row saying "present 1st to 30th" silently leaving
 * four days untouched is exactly the kind of thing that shows up a fortnight
 * later as a wrong salary. Every day the upload declined to write says which
 * day and why.
 */
public record AttendanceBulkUploadResponse(
        int totalRows,
        int daysApplied,
        int daysSkipped,
        List<AttendanceRecordResponse> applied,
        List<SkippedDay> skipped,
        List<BulkImportResult.RowError> errors
) {

    /** One day inside an uploaded range that was left as it was, and why. */
    public record SkippedDay(String userId, LocalDate date, Reason reason, String detail) {
    }

    public enum Reason {
        /** Payroll has been generated for the period - unlock the month first. */
        LOCKED,
        /** Approved leave already explains this day; the leave workflow wins over a blanket range. */
        ON_LEAVE,
        /** A week off or a holiday. Bulk upload never restates a non-working day - use the single-day correction endpoint to record work on one. */
        NON_WORKING_DAY,
        /** Nothing on the roster for this date, so there is no shift to declare hours against. */
        NOT_ROSTERED
    }
}

package com.accusharp.hrms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.YearMonth;
import java.util.List;

/**
 * Generates the stored attendance for a period, for one employee, several, or
 * everybody.
 */
@Data
public class AttendanceGenerationRequest {

    @NotNull
    @JsonFormat(pattern = "yyyy-MM")
    private YearMonth month;

    /** Null or empty means every active employee. */
    private List<String> userIds;

    /** Whoever ran it - must hold the HR or ADMIN role. */
    @NotBlank
    private String generatedBy;

    /**
     * Off by default, and that default is the point: a rerun picks up punches
     * that arrived late without discarding what an admin already corrected.
     */
    private boolean overwriteManual;

    /**
     * Computes everything and reports what would change, without writing a
     * single row. Attendance decides pay, so a run that moves a month's loss of
     * pay should be readable before it is committed - particularly when
     * regenerating a past period, where the previous numbers have already been
     * seen and possibly acted on.
     */
    private boolean dryRun;

    /**
     * Whether a day with no roster row still becomes an attendance day.
     *
     * <p><b>On by default.</b> Without it, an employee nobody rostered produces
     * no rows at all: HR opens the month and sees a blank sheet with nothing to
     * review or correct, and payroll - which derives loss of pay from
     * {@code workingDays - presentDays - paidLeave} - sees zero working days
     * and pays the month in full. A missing roster is an HR oversight, not
     * evidence that the employee was not expected to work, so the day is
     * written blank ({@code shiftCode} null, no hours) and {@code ABSENT},
     * which is visible, reviewable and correctable.
     *
     * <p>Set it to {@code false} for a company that rosters deliberately
     * sparsely - casual or contract staff who are only scheduled on the days
     * they actually work - where an unrostered day genuinely means "not a
     * working day" and marking it absent would invent loss of pay.
     *
     * <p>Days outside the employee's joining/relieving window are never filled
     * either way: nobody is absent before they were hired.
     */
    private boolean includeUnrostered = true;
}

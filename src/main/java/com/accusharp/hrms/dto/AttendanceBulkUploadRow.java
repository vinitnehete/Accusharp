package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * One row of a bulk attendance upload - a stretch of days declared outright,
 * for people whose attendance the biometric device never sees.
 *
 * <p>Deliberately a date <em>range</em> rather than a single day: the case
 * this exists for is "this director was present all September", and making
 * that twenty-six rows instead of one is how a monthly chore stops getting
 * done. {@code toDate} is optional and defaults to {@code fromDate}, so a
 * single-day correction is the same shape with a column left blank.
 *
 * <p>No punch times, by design. Hours follow the rostered shift and overtime
 * is zero - there is no clock-in to measure lateness or extra hours against,
 * which is the whole point for staff who do not punch. Where real times are
 * known (a device outage), {@code PUT /api/attendance/{userId}/{date}} still
 * takes them and runs them through the normal calculation.
 */
@Data
public class AttendanceBulkUploadRow {

    /** Employee.userId business key. */
    @NotBlank
    private String userId;

    @NotNull
    private LocalDate fromDate;

    /** Optional - a blank column means this row is just {@code fromDate}. */
    private LocalDate toDate;

    /** Optional - defaults to {@link AttendanceStatus#PRESENT}, which is what a range is almost always declaring. */
    private AttendanceStatus status;

    /** Mandatory, same rule as a single-day correction: attendance decides pay, and a change to it without a reason is not auditable. */
    @NotBlank
    @Size(max = 500)
    private String remarks;

    /** Always overwritten with the caller's own authenticated username - never trusted from the client. */
    private String uploadedBy;

    public LocalDate effectiveToDate() {
        return toDate == null ? fromDate : toDate;
    }

    public AttendanceStatus effectiveStatus() {
        return status == null ? AttendanceStatus.PRESENT : status;
    }
}

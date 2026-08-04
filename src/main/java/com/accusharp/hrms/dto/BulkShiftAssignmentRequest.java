package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Assign one shift to many employees across a date range. */
@Data
public class BulkShiftAssignmentRequest {

    @NotEmpty
    private List<String> userIds;

    @NotNull
    private LocalDate fromDate;

    @NotNull
    private LocalDate toDate;

    @NotNull
    private String shiftCode;

    /** Days marked as weekly off inside the range; empty means none. */
    private Set<DayOfWeek> weekOffDays;

    /** Skip company holidays instead of scheduling them. */
    private boolean skipHolidays = true;

    /** Overwrite an existing assignment instead of failing on conflict. */
    private boolean overwriteExisting;

    private String assignedBy;
}

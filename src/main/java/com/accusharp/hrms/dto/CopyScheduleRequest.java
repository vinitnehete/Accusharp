package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.YearMonth;
import java.util.List;

/** Copy a month's roster onto another month, matching day-of-month. */
@Data
public class CopyScheduleRequest {

    @NotEmpty
    private List<String> userIds;

    @NotNull
    private YearMonth sourceMonth;

    @NotNull
    private YearMonth targetMonth;

    private boolean overwriteExisting;

    private String assignedBy;
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveRequestPayload {

    @NotBlank
    private String userId;

    @NotNull
    private LeaveType leaveType;

    @NotNull
    private LocalDate fromDate;

    @NotNull
    private LocalDate toDate;

    /** Half-day durations are only valid for a single-day request. */
    @NotNull
    private LeaveDuration duration;

    @Size(max = 500)
    private String reason;
}

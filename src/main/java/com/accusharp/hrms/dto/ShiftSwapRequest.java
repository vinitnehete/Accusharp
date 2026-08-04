package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** Swap two employees' shifts on a date (both must already be scheduled). */
@Data
public class ShiftSwapRequest {

    @NotBlank
    private String firstUserId;

    @NotBlank
    private String secondUserId;

    @NotNull
    private LocalDate shiftDate;

    private String assignedBy;
}

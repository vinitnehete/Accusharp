package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** Assign one employee one shift on one date. */
@Data
public class ShiftAssignmentRequest {

    @NotBlank
    private String userId;

    @NotNull
    private LocalDate shiftDate;

    @NotBlank
    private String shiftCode;

    private boolean weekOff;

    /** Supervisor performing the assignment; validated against the mapping. */
    private String assignedBy;
}

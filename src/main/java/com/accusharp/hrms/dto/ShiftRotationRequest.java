package com.accusharp.hrms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Auto-rotation: each employee starts on a different shift in the cycle and
 * advances one position every {@code rotationDays}.
 */
@Data
public class ShiftRotationRequest {

    @NotEmpty
    private List<String> userIds;

    @NotEmpty
    private List<String> shiftCycle;

    @NotNull
    private LocalDate fromDate;

    @NotNull
    private LocalDate toDate;

    @Min(1)
    private int rotationDays = 7;

    private Set<DayOfWeek> weekOffDays;

    private boolean skipHolidays = true;

    private String assignedBy;
}

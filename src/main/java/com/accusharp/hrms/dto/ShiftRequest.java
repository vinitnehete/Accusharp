package com.accusharp.hrms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
public class ShiftRequest {

    @NotBlank
    @Size(max = 30)
    private String shiftCode;

    @NotBlank
    @Size(max = 60)
    private String shiftName;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    @Positive
    private int workingHours;

    @Min(0)
    private int breakMinutes;

    @Min(0)
    private int graceMinutes;

    /** Minutes after the scheduled end that still count as this shift's work. */
    @Min(0)
    private int overtimeWindowMinutes = 240;
}

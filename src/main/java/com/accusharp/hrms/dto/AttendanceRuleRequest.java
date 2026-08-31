package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AttendanceRuleRequest {

    @Min(0)
    private int entryWindowBufferMinutes;

    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
    private BigDecimal fullDayThresholdPercent;

    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
    private BigDecimal halfDayThresholdPercent;
}

package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalaryRuleRequest {

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal basicDaPercent;

    /** Government-notified minimum Basic+DA; 0 means no floor applies. */
    @NotNull @DecimalMin("0")
    private BigDecimal basicDaMinimumThreshold;

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal hraPercent;

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal conveyancePercent;

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal educationPercent;

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal pfPercent;

    @NotNull @DecimalMin("0") @DecimalMax("100")
    private BigDecimal esicPercent;

    @NotNull @DecimalMin("0")
    private BigDecimal esicWageCeiling;

    @NotNull @DecimalMin("0")
    private BigDecimal ptUpperThreshold;

    @NotNull @DecimalMin("0")
    private BigDecimal ptUpperAmount;

    @NotNull @DecimalMin("0")
    private BigDecimal ptLowerThreshold;

    @NotNull @DecimalMin("0")
    private BigDecimal ptLowerAmount;

    @Min(1) @Max(31)
    private int dayWiseDaysInMonth;

    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("24")
    private BigDecimal standardHoursPerDay;

    @NotNull @DecimalMin("0")
    private BigDecimal overtimeRateMultiplier;

    /** Flat MLWF amount deducted from the employee in June and December only; 0 means not applicable. */
    @NotNull @DecimalMin("0")
    private BigDecimal mlwfAmount;
}

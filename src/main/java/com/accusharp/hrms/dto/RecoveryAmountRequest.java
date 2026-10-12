package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A manual movement on a recovery outside payroll - a waiver (write off part
 * of what is owed), or an adjustment (an amount settled in cash or by bank
 * transfer, or a wrongly-entered principal being corrected).
 *
 * <p>Both reduce the outstanding and both write a ledger row, so neither can
 * quietly change a balance the way editing the figure directly would.
 */
@Data
public class RecoveryAmountRequest {

    @NotNull @DecimalMin(value = "0", inclusive = false)
    private BigDecimal amount;

    /** Why - required, because an unexplained write-off is exactly what the ledger exists to prevent. */
    @NotNull @Size(min = 1, max = 300)
    private String remarks;
}

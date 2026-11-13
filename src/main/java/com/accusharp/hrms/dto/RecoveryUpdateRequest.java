package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.RecoveryStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Changing the terms of an open recovery - reschedule the instalment, move
 * the start period, or put it on hold while the employee is on unpaid leave.
 *
 * <p>Deliberately cannot touch {@code principalAmount} or
 * {@code outstandingAmount}: what was issued is history, and the balance only
 * ever moves through a ledger transaction (see {@link RecoveryAmountRequest}).
 * Every field is optional - null means "leave as it is".
 */
@Data
public class RecoveryUpdateRequest {

    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal installmentAmount;

    @Min(1) @Max(12)
    private Integer startMonth;

    @Min(2000) @Max(2100)
    private Integer startYear;

    /** ACTIVE or ON_HOLD only - closing and cancelling have their own endpoints, because both settle the balance rather than just pausing it. */
    private RecoveryStatus status;

    @Size(max = 300)
    private String remarks;
}

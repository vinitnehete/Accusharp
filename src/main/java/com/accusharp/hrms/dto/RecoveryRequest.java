package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.RecoveryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Issuing a recovery: a loan disbursed, an advance paid out, a fine levied,
 * or any other amount to be recovered from salary.
 *
 * <p>Everything derived - the outstanding balance, when it closes, how it is
 * split across periods - is computed server-side; this carries only what a
 * human actually decides.
 */
@Data
public class RecoveryRequest {

    /** Employee.userId business key. */
    @NotBlank
    private String employeeId;

    @NotNull
    private RecoveryType type;

    /** What is being handed over or levied. */
    @NotNull @DecimalMin(value = "0", inclusive = false)
    private BigDecimal principalAmount;

    /**
     * What to recover each period. Optional: left null, a {@code LOAN}
     * derives it from {@link #tenureMonths} (or falls back to the whole
     * principal when that is absent too), and every other type takes the
     * whole principal at the next payroll that can afford it.
     */
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal installmentAmount;

    /** Convenience alternative to {@link #installmentAmount} for a loan: principal spread evenly over this many months. */
    @Min(1) @Max(600)
    private Integer tenureMonths;

    /** First recovery period: supply both month and year, no earlier than the issue month. Defaults to next month for a loan, and the issue month for other types. */
    @Min(1) @Max(12)
    private Integer startMonth;

    @Min(2000) @Max(2100)
    private Integer startYear;

    /** Past, current or future issue date; defaults to today when absent. Registration never rewrites paid payroll. */
    private LocalDate issuedOn;

    @Size(max = 300)
    private String reason;

    @Size(max = 300)
    private String remarks;

    /** Always overwritten with the authenticated caller's own username - never trusted from the client. */
    private String issuedBy;
}

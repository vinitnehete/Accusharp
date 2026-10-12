package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Payroll inputs. Everything else - attendance, leave, LOP, proration, PF,
 * ESIC, PT and net pay - is derived server-side.
 *
 * <p>The four recovery amounts below ({@link #advanceDeduction},
 * {@link #loanDeduction}, {@link #fine}, {@link #otherDeduction}) are
 * deliberately <em>nullable</em>, unlike {@link #tds} and {@link #canteen}
 * which still default to zero. Null does not mean zero here - it means "take
 * whatever the employee's recovery ledger says is due this period" (see
 * {@code RecoveryService.plan}). That distinction is the whole reason the
 * whole-company {@code generate-all} run and the CSV bulk upload now recover
 * anything at all: previously they sent zero for every employee and quietly
 * collected nothing against any outstanding loan.
 *
 * <p>Supplying a value overrides the schedule for this period only, and an
 * explicit {@code 0} is a real instruction - "skip the instalment this month"
 * - not an absent field. Anything asked for beyond what the ledger holds is
 * treated as a one-off deduction for this period; if the month cannot afford
 * it, the shortfall is raised as a recovery so the next period takes it
 * rather than it being silently lost.
 */
@Data
public class PayrollRequest {

    /** Employee.userId business key. */
    @NotBlank
    private String employeeId;

    @NotNull @Min(1) @Max(12)
    private Integer month;

    @NotNull @Min(2000)
    private Integer year;

    /** Null: recover the scheduled advance instalment. A value: override it for this period. */
    @DecimalMin("0")
    private BigDecimal advanceDeduction;

    /** Null: recover the scheduled loan instalment. A value: override it for this period. */
    @DecimalMin("0")
    private BigDecimal loanDeduction;

    /** Null: recover whatever fines are outstanding. A value: override, or levy an ad-hoc fine this period. */
    @DecimalMin("0")
    private BigDecimal fine;

    /** Null: recover whatever other recoveries are outstanding. A value: override, or deduct an ad-hoc amount this period. */
    @DecimalMin("0")
    private BigDecimal otherDeduction;

    @DecimalMin("0")
    private BigDecimal tds = BigDecimal.ZERO;

    @DecimalMin("0")
    private BigDecimal canteen = BigDecimal.ZERO;

    @DecimalMin("0")
    private BigDecimal bonus = BigDecimal.ZERO;

    @DecimalMin("0")
    private BigDecimal incentive = BigDecimal.ZERO;

    private String generatedBy;
}

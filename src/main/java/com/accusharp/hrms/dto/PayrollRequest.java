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

    @DecimalMin("0")
    private BigDecimal advanceDeduction = BigDecimal.ZERO;

    @DecimalMin("0")
    private BigDecimal loanDeduction = BigDecimal.ZERO;

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

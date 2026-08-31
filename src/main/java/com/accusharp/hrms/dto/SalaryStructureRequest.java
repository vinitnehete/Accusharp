package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Manual override for the components {@link com.accusharp.hrms.service.calculation.SalaryCalculationService}
 * would otherwise derive from {@link com.accusharp.hrms.entity.SalaryRule}. Applying this marks the
 * employee's salary structure as overridden - see {@code EmployeeService#updateSalaryStructure} - until
 * {@code EmployeeService#regenerateSalaryStructure} recomputes it from the rule again.
 */
@Data
public class SalaryStructureRequest {

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal basicDA;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal hra;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal conveyanceAllowance;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal educationAllowance;

    /**
     * The three below are optional, and null means "leave as it is". They exist
     * so a bulk upload can carry a complete salary picture in one row, while the
     * single-employee endpoint - which has only ever sent the four components
     * above - keeps working unchanged.
     *
     * <p>Medical and other allowances are fixed amounts: no rule derives them,
     * so an override never touched them and setting them here is simply setting
     * them.
     */
    @DecimalMin(value = "0")
    private BigDecimal medicalAllowance;

    @DecimalMin(value = "0")
    private BigDecimal otherAllowance;

    /**
     * The headline gross. Setting it here is a <em>correction</em> of what the
     * figure is, not a raise: it writes no {@link SalaryRevisionRequest} history
     * row, so payroll treats the new value as having applied all along rather
     * than prorating from a date. Use {@code /salary-revision} for an actual
     * increase.
     */
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal grossSalary;
}

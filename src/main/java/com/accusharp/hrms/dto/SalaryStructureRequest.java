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
}

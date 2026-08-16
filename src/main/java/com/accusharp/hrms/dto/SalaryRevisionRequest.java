package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.SalaryRevisionReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A salary hike/promotion/correction for one employee. {@code newGrossSalary}
 * replaces the employee's current gross salary; the structure (basicDA/hra/
 * conveyanceAllowance/educationAllowance) is then re-derived from the
 * company's current {@code SalaryRule} - unless the employee's structure is
 * overridden, in which case the four replacement fields below are required,
 * since a frozen structure never follows grossSalary on its own.
 */
@Data
public class SalaryRevisionRequest {

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal newGrossSalary;

    @NotNull
    private LocalDate effectiveDate;

    @NotNull
    private SalaryRevisionReason reason;

    private String remarks;

    // Required only when the employee's salary structure is currently overridden.
    @DecimalMin(value = "0")
    private BigDecimal basicDA;

    @DecimalMin(value = "0")
    private BigDecimal hra;

    @DecimalMin(value = "0")
    private BigDecimal conveyanceAllowance;

    @DecimalMin(value = "0")
    private BigDecimal educationAllowance;
}

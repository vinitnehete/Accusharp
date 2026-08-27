package com.accusharp.hrms.dto;

import java.math.BigDecimal;

/**
 * What one row of a bulk salary-structure override does, or would do.
 *
 * <p>{@code totalWage} is what the components actually add up to
 * (basicDA + hra + conveyance + education + medical + other), and
 * {@code differenceFromGross} is how far that lands from the gross the row
 * leaves in place. Nothing forces the two to agree - the single-employee
 * endpoint has never required it either - but an override is exactly where
 * they quietly stop agreeing, so the gap is reported rather than left to be
 * discovered in a payslip.
 *
 * <p>{@code previousGrossSalary} and {@code grossSalary} differ only when the
 * row carried a gross of its own. That is a <em>correction</em> and writes no
 * revision history, so payroll reads the new figure as having applied all
 * along - which is why it is reported as a before-and-after rather than folded
 * into one number.
 *
 * <p>{@code alreadyOverridden} distinguishes an employee whose structure was
 * already frozen from one this file freezes for the first time. That second
 * group is the consequential one: from here on their components no longer
 * follow gross salary, so every future revision must restate all four.
 */
public record SalaryStructurePreview(
        String userId,
        String employeeName,
        BigDecimal previousGrossSalary,
        BigDecimal grossSalary,
        BigDecimal basicDA,
        BigDecimal hra,
        BigDecimal conveyanceAllowance,
        BigDecimal educationAllowance,
        BigDecimal medicalAllowance,
        BigDecimal otherAllowance,
        BigDecimal totalWage,
        BigDecimal differenceFromGross,
        boolean alreadyOverridden,
        boolean applied
) {
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.SalaryRevisionReason;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one row of a bulk salary revision does, or would do.
 *
 * <p>The same shape is returned whether the run was a dry run or a real one,
 * so the file an admin reviews and the file they commit are read identically -
 * these rows feed payroll's own salary segmentation, not just an audit log, so
 * being able to read them before committing is the point.
 */
public record SalaryRevisionPreview(
        String userId,
        String employeeName,
        BigDecimal previousGrossSalary,
        BigDecimal newGrossSalary,
        BigDecimal hikePercent,
        LocalDate effectiveDate,
        SalaryRevisionReason reason,
        BigDecimal medicalAllowance,
        BigDecimal otherAllowance,
        boolean applied
) {
}

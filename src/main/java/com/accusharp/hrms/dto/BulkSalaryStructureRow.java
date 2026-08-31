package com.accusharp.hrms.dto;

/**
 * One CSV row of a bulk salary-structure override: which employee, and the
 * four components to freeze. Kept separate from {@link SalaryStructureRequest}
 * because the single-employee endpoint identifies the employee in the path and
 * a bulk file has to carry it in the row.
 */
public record BulkSalaryStructureRow(String userId, SalaryStructureRequest request) {
}

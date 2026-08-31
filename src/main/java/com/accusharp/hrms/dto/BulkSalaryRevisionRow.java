package com.accusharp.hrms.dto;

/**
 * One CSV row of a bulk salary revision: which employee, and the revision
 * itself. Kept separate from {@link SalaryRevisionRequest} because the
 * single-employee endpoint identifies the employee in the path, and a bulk
 * file has to carry it in the row.
 */
public record BulkSalaryRevisionRow(String userId, SalaryRevisionRequest request) {
}

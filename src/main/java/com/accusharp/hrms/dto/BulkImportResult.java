package com.accusharp.hrms.dto;

import java.util.List;

/**
 * Outcome of a row-by-row bulk operation (CSV import or a JSON list of
 * entries). Every row is attempted independently and a bad row never aborts
 * the rest of the batch - {@code errors} carries what went wrong and for
 * which row, {@code succeeded} carries what was actually written.
 */
public record BulkImportResult<T>(
        int totalRows,
        int successCount,
        int failureCount,
        List<T> succeeded,
        List<RowError> errors
) {

    public static <T> BulkImportResult<T> of(int totalRows, List<T> succeeded, List<RowError> errors) {
        return new BulkImportResult<>(totalRows, succeeded.size(), errors.size(), succeeded, errors);
    }

    /** {@code identifier} is the row's userId/employeeCode when known, otherwise the raw row number. */
    public record RowError(int rowNumber, String identifier, String message) {
    }
}

package com.accusharp.hrms.util;

/**
 * One parsed CSV data row (1-indexed, header excluded): either {@code value}
 * is populated, or {@code error} is - never both. Lets a parser fail a single
 * malformed row without aborting the rest of the file.
 */
public record ParsedCsvRow<T>(int rowNumber, T value, String error) {

    public static <T> ParsedCsvRow<T> ok(int rowNumber, T value) {
        return new ParsedCsvRow<>(rowNumber, value, null);
    }

    public static <T> ParsedCsvRow<T> failed(int rowNumber, String error) {
        return new ParsedCsvRow<>(rowNumber, null, error);
    }

    public boolean isOk() {
        return error == null;
    }
}

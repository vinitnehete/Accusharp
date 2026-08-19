package com.accusharp.hrms.util;

import com.accusharp.hrms.exception.BusinessRuleException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared row-by-row CSV parsing loop for every bulk-import endpoint
 * ({@link EmployeeCsvParser}, {@link PayrollCsvParser},
 * {@link ShiftAssignmentCsvParser}, {@link LeaveCsvParser}) - one malformed
 * row becomes a {@link ParsedCsvRow#failed} entry instead of aborting the
 * whole file, identical contract for all four.
 *
 * <p>Also the single place a row-count cap is enforced, independent of the
 * {@code spring.servlet.multipart.max-file-size} byte limit - a file well
 * under that byte limit can still carry an unreasonable number of short
 * rows, each triggering a full synchronous per-row service call (employee
 * creation + password hashing, payroll generation, ...) on the request
 * thread.
 */
public final class CsvRowParser {

    /** Past this many data rows, the upload is rejected outright rather than processed. */
    public static final int MAX_ROWS = 5000;

    static final CSVFormat DEFAULT_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private CsvRowParser() {
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(CSVRecord record);
    }

    public static <T> List<ParsedCsvRow<T>> parse(MultipartFile file, RowMapper<T> mapper) {
        List<ParsedCsvRow<T>> rows = new ArrayList<>();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = DEFAULT_FORMAT.parse(reader)) {
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                if (rowNumber > MAX_ROWS) {
                    throw new BusinessRuleException(
                            "CSV file has more than " + MAX_ROWS + " data rows - split it into smaller batches");
                }
                try {
                    rows.add(ParsedCsvRow.ok(rowNumber, mapper.map(record)));
                } catch (RuntimeException e) {
                    rows.add(ParsedCsvRow.failed(rowNumber, e.getMessage()));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            throw new BusinessRuleException("Could not read the uploaded CSV file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown by commons-csv itself for a missing/duplicate header row.
            throw new BusinessRuleException("Malformed CSV header: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessRuleException("CSV file has no data rows");
        }
        return rows;
    }

    /**
     * Sheets exported from Excel/Sheets commonly format numeric columns with
     * thousands separators and/or a currency symbol, e.g. {@code "41,000.00"}
     * or {@code "₹41,000.00"}; strip that decoration so those values still
     * parse as plain numbers instead of failing with a raw parser error the
     * uploader can't act on.
     */
    public static String stripThousandsSeparators(String value) {
        return value.replaceAll("[,₹$\\s]", "");
    }

    public static String get(CSVRecord record, String column) {
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String required(CSVRecord record, String column) {
        String value = get(record, column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value;
    }
}

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared row-by-row CSV parsing loop for every bulk-import endpoint
 * ({@link EmployeeCsvParser}, {@link PayrollCsvParser},
 * {@link ShiftAssignmentCsvParser}, {@link LeaveCsvParser}) - one malformed
 * row becomes a {@link ParsedCsvRow#failed} entry instead of aborting the
 * whole file, identical contract for all four.
 *
 * <p>The header row isn't assumed to be line 1: the downloadable Excel
 * templates (see {@code employeeTemplate.js}) put a title, instructions and
 * a legend above the real header row for readability, and Excel's "Save As
 * CSV" carries those rows straight into the file unchanged. Instead, each
 * caller passes a column name it knows must appear in its header (one of its
 * own required columns); {@link #parse} scans for the first row containing
 * that name and treats everything above it as decoration to skip. The same
 * pass also strips the trailing {@code " *"} the templates append to
 * required-column headers, since that marker isn't part of the column name
 * the row mappers look up.
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

    private static final CSVFormat PROBE_FORMAT = CSVFormat.DEFAULT.builder()
            .setIgnoreSurroundingSpaces(true)
            .build();

    private CsvRowParser() {
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(CSVRecord record);
    }

    private record HeaderLocation(int lineIndex, List<String> names) {
    }

    /**
     * @param headerHintColumn a column name (case-insensitive) that must appear in the real
     *                          header row - typically one of the caller's own required columns.
     *                          Used to find that row among any decorative rows above it.
     */
    public static <T> List<ParsedCsvRow<T>> parse(MultipartFile file, String headerHintColumn, RowMapper<T> mapper) {
        return parse(file, new String[]{headerHintColumn}, mapper);
    }

    /**
     * Same, for a file whose header row may be spelled several ways - a salary
     * export headed {@code Basic + DA} has to be located as surely as one
     * headed {@code basicDA}, and the header row is found before any column
     * alias is consulted, so the hint itself has to know the alternatives.
     */
    public static <T> List<ParsedCsvRow<T>> parse(MultipartFile file, String[] headerHintColumns, RowMapper<T> mapper) {
        HeaderLocation header = locateHeader(file, headerHintColumns);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(header.names().toArray(new String[0]))
                .setIgnoreHeaderCase(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();

        List<ParsedCsvRow<T>> rows = new ArrayList<>();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            int lineIndex = 0;
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                if (lineIndex++ <= header.lineIndex()) {
                    continue;
                }
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
            // Thrown by commons-csv itself for a duplicate header name.
            throw new BusinessRuleException("Malformed CSV header: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessRuleException("CSV file has no data rows");
        }
        return rows;
    }

    private static HeaderLocation locateHeader(MultipartFile file, String[] headerHintColumns) {
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser probe = PROBE_FORMAT.parse(reader)) {
            int lineIndex = 0;
            for (CSVRecord record : probe) {
                for (String cell : record) {
                    if (matchesAny(stripHeaderMarker(cell), headerHintColumns)) {
                        List<String> names = new ArrayList<>();
                        for (String c : record) {
                            names.add(stripHeaderMarker(c));
                        }
                        return new HeaderLocation(lineIndex, names);
                    }
                }
                lineIndex++;
            }
        } catch (IOException | UncheckedIOException e) {
            throw new BusinessRuleException("Could not read the uploaded CSV file: " + e.getMessage());
        }
        throw new BusinessRuleException(
                "Could not find a header row containing '" + headerHintColumns[0]
                        + "' - check the file has the expected column names");
    }

    private static boolean matchesAny(String cell, String[] candidates) {
        for (String candidate : candidates) {
            if (cell.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Strips the trailing " *" the downloadable templates append to required-column headers. */
    private static String stripHeaderMarker(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("*")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
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

    /**
     * ISO {@code yyyy-MM-dd}. With {@code required} the missing-column error is
     * {@link #required}'s; without it an absent column yields {@code null}. A
     * value that is present but unparseable is an error either way - a typo is
     * never silently treated as "not supplied".
     */
    public static LocalDate parseDate(CSVRecord record, String column, boolean required) {
        String value = required ? required(record, column) : get(record, column);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    /** Absent or blank reads as {@code false}; anything Java doesn't read as {@code true} is {@code false}. */
    public static boolean parseBoolean(CSVRecord record, String column) {
        String value = get(record, column);
        return value != null && Boolean.parseBoolean(value);
    }

    /**
     * A monetary or decimal value, tolerating the thousands separators and
     * currency symbols a spreadsheet export leaves behind. With
     * {@code required} a missing column is an error; without it, {@code null}.
     * A value that is present but unparseable is an error either way - a typo
     * in a salary figure must never be read as "not supplied".
     */
    public static BigDecimal parseDecimal(CSVRecord record, String column, boolean required) {
        String value = required ? required(record, column) : get(record, column);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(stripThousandsSeparators(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, got '" + value + "'");
        }
    }

    /**
     * A decimal under whichever of several accepted spellings the file
     * actually carries. Salary columns get exported from reports under their
     * display headings - {@code Basic + DA}, {@code Con. Allow} - as often as
     * under their field names, and rejecting a file over a heading nobody
     * chose is not a useful failure. The first name is the canonical one and
     * is what error messages quote.
     */
    public static BigDecimal parseDecimalAny(CSVRecord record, boolean required, String... columns) {
        for (String column : columns) {
            if (record.isMapped(column) && get(record, column) != null) {
                return parseDecimal(record, column, false);
            }
        }
        if (required) {
            throw new IllegalArgumentException(columns[0] + " is required");
        }
        return null;
    }

    /**
     * Case-insensitive enum lookup. The failure message lists the accepted
     * constants, because the caller of a bulk import cannot see the enum.
     */
    public static <E extends Enum<E>> E parseEnum(CSVRecord record, String column, Class<E> type, boolean required) {
        String value = required ? required(record, column) : get(record, column);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be one of " + Arrays.toString(type.getEnumConstants())
                    + ", got '" + value + "'");
        }
    }
}

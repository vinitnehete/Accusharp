package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import com.accusharp.hrms.exception.BusinessRuleException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an uploaded CSV into {@link ShiftAssignmentRequest} rows - one row
 * per employee-day-shift, so a single file can roster a whole team across
 * different shifts and different days in one upload.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code userId,
 * shiftDate, shiftCode, weekOff}. {@code weekOff} is optional and defaults to
 * {@code false}. Every row is parsed independently, same as {@link
 * EmployeeCsvParser}.
 */
public final class ShiftAssignmentCsvParser {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private ShiftAssignmentCsvParser() {
    }

    public static List<ParsedCsvRow<ShiftAssignmentRequest>> parse(MultipartFile file) {
        List<ParsedCsvRow<ShiftAssignmentRequest>> rows = new ArrayList<>();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                try {
                    rows.add(ParsedCsvRow.ok(rowNumber, toRequest(record)));
                } catch (RuntimeException e) {
                    rows.add(ParsedCsvRow.failed(rowNumber, e.getMessage()));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            throw new BusinessRuleException("Could not read the uploaded CSV file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Malformed CSV header: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessRuleException("CSV file has no data rows");
        }
        return rows;
    }

    private static ShiftAssignmentRequest toRequest(CSVRecord record) {
        ShiftAssignmentRequest request = new ShiftAssignmentRequest();
        request.setUserId(required(record, "userId"));
        request.setShiftDate(parseDate(record, "shiftDate"));
        request.setShiftCode(required(record, "shiftCode"));
        request.setWeekOff(parseBoolean(record, "weekOff"));
        return request;
    }

    private static String get(CSVRecord record, String column) {
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(CSVRecord record, String column) {
        String value = get(record, column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value;
    }

    private static LocalDate parseDate(CSVRecord record, String column) {
        String value = required(record, column);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    private static boolean parseBoolean(CSVRecord record, String column) {
        String value = get(record, column);
        return value != null && Boolean.parseBoolean(value);
    }
}

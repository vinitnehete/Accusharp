package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
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
 * Turns an uploaded CSV into {@link LeaveHrDirectRequest} rows for HR's bulk
 * "backfill already-approved leaves" import - the CSV analog of {@code
 * POST /api/leaves/hr-create}, one row per employee-period, same as {@link
 * EmployeeCsvParser} is to a single employee create.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code userId,
 * leaveType, fromDate, toDate, duration, reason}. {@code duration} is
 * optional and defaults to {@code FULL_DAY}; {@code reason} is optional.
 * Every row is parsed independently: one malformed row becomes a {@link
 * ParsedCsvRow#failed} entry rather than aborting the file.
 */
public final class LeaveCsvParser {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private LeaveCsvParser() {
    }

    public static List<ParsedCsvRow<LeaveHrDirectRequest>> parse(MultipartFile file) {
        List<ParsedCsvRow<LeaveHrDirectRequest>> rows = new ArrayList<>();
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

    private static LeaveHrDirectRequest toRequest(CSVRecord record) {
        LeaveHrDirectRequest request = new LeaveHrDirectRequest();
        request.setUserId(required(record, "userId"));
        request.setLeaveType(parseEnum(record, "leaveType", LeaveType.class, required(record, "leaveType")));
        request.setFromDate(parseDate(record, "fromDate"));
        request.setToDate(parseDate(record, "toDate"));
        String duration = get(record, "duration");
        request.setDuration(duration == null
                ? LeaveDuration.FULL_DAY
                : parseEnum(record, "duration", LeaveDuration.class, duration));
        request.setReason(get(record, "reason"));
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

    private static <E extends Enum<E>> E parseEnum(CSVRecord record, String column, Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be one of " + java.util.Arrays.toString(type.getEnumConstants())
                    + ", got '" + value + "'");
        }
    }
}

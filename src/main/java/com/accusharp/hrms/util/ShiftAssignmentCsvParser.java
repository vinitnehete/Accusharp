package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    private ShiftAssignmentCsvParser() {
    }

    public static List<ParsedCsvRow<ShiftAssignmentRequest>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, ShiftAssignmentCsvParser::toRequest);
    }

    private static ShiftAssignmentRequest toRequest(CSVRecord record) {
        ShiftAssignmentRequest request = new ShiftAssignmentRequest();
        request.setUserId(CsvRowParser.required(record, "userId"));
        request.setShiftDate(parseDate(record, "shiftDate"));
        request.setShiftCode(CsvRowParser.required(record, "shiftCode"));
        request.setWeekOff(parseBoolean(record, "weekOff"));
        return request;
    }

    private static LocalDate parseDate(CSVRecord record, String column) {
        String value = CsvRowParser.required(record, column);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    private static boolean parseBoolean(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        return value != null && Boolean.parseBoolean(value);
    }
}

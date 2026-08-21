package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    private LeaveCsvParser() {
    }

    public static List<ParsedCsvRow<LeaveHrDirectRequest>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, "leaveType", LeaveCsvParser::toRequest);
    }

    private static LeaveHrDirectRequest toRequest(CSVRecord record) {
        LeaveHrDirectRequest request = new LeaveHrDirectRequest();
        request.setUserId(CsvRowParser.required(record, "userId"));
        request.setLeaveType(parseEnum(record, "leaveType", LeaveType.class, CsvRowParser.required(record, "leaveType")));
        request.setFromDate(parseDate(record, "fromDate"));
        request.setToDate(parseDate(record, "toDate"));
        String duration = CsvRowParser.get(record, "duration");
        request.setDuration(duration == null
                ? LeaveDuration.FULL_DAY
                : parseEnum(record, "duration", LeaveDuration.class, duration));
        request.setReason(CsvRowParser.get(record, "reason"));
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

    private static <E extends Enum<E>> E parseEnum(CSVRecord record, String column, Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be one of " + java.util.Arrays.toString(type.getEnumConstants())
                    + ", got '" + value + "'");
        }
    }
}

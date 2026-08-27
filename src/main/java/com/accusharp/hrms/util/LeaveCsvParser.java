package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

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
        request.setLeaveType(CsvRowParser.parseEnum(record, "leaveType", LeaveType.class, true));
        request.setFromDate(CsvRowParser.parseDate(record, "fromDate", true));
        request.setToDate(CsvRowParser.parseDate(record, "toDate", true));
        LeaveDuration duration = CsvRowParser.parseEnum(record, "duration", LeaveDuration.class, false);
        request.setDuration(duration == null ? LeaveDuration.FULL_DAY : duration);
        request.setReason(CsvRowParser.get(record, "reason"));
        return request;
    }
}

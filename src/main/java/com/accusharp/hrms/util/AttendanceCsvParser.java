package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.AttendanceBulkUploadRow;
import com.accusharp.hrms.enums.AttendanceStatus;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Turns an uploaded CSV into {@link AttendanceBulkUploadRow} rows - declaring
 * attendance for people the biometric device never sees, or for any period a
 * device outage left without punches.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code userId,
 * fromDate, toDate, status, remarks}. {@code userId}, {@code fromDate} and
 * {@code remarks} are required; {@code toDate} defaults to {@code fromDate}
 * (so a single day is one blank column, not a different file format) and
 * {@code status} defaults to {@code PRESENT}, which is what a range almost
 * always declares.
 *
 * <p>A whole month for one person is therefore one line:
 * <pre>
 * userId,fromDate,toDate,status,remarks
 * DIR001,2026-09-01,2026-09-30,,Management - exempt from biometric
 * </pre>
 *
 * <p>Every row is parsed independently: one malformed row becomes a
 * {@link ParsedCsvRow#failed} entry rather than aborting the file, the same
 * contract as every other bulk import.
 */
public final class AttendanceCsvParser {

    private AttendanceCsvParser() {
    }

    public static List<ParsedCsvRow<AttendanceBulkUploadRow>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, "fromDate", AttendanceCsvParser::toRow);
    }

    private static AttendanceBulkUploadRow toRow(CSVRecord record) {
        AttendanceBulkUploadRow row = new AttendanceBulkUploadRow();
        row.setUserId(CsvRowParser.required(record, "userId"));
        row.setFromDate(CsvRowParser.parseDate(record, "fromDate", true));
        row.setToDate(CsvRowParser.parseDate(record, "toDate", false));
        row.setStatus(CsvRowParser.parseEnum(record, "status", AttendanceStatus.class, false));
        row.setRemarks(CsvRowParser.required(record, "remarks"));
        return row;
    }
}

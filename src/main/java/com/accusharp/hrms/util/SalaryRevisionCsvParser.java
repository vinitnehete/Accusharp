package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.BulkSalaryRevisionRow;
import com.accusharp.hrms.dto.SalaryRevisionRequest;
import com.accusharp.hrms.enums.SalaryRevisionReason;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Turns an uploaded CSV into one salary revision per row - an appraisal cycle
 * in a single file instead of one form per employee.
 *
 * <p>Expected header (case-insensitive, order-independent):
 * {@code userId, newGrossSalary, effectiveDate, reason, remarks}.
 *
 * <p>The four structure columns - {@code basicDA, hra, conveyanceAllowance,
 * educationAllowance} - are optional and only read for employees whose salary
 * structure is <em>overridden</em>. A normal employee's structure is re-derived
 * from the company's {@code SalaryRule}, so supplying them changes nothing;
 * an overridden employee's is frozen and never follows gross on its own, so
 * omitting them fails that row rather than leaving it silently stale. See
 * {@link SalaryRevisionRequest}'s Javadoc.
 *
 * <p>Every row is parsed independently, same as {@link EmployeeCsvParser}.
 */
public final class SalaryRevisionCsvParser {

    private SalaryRevisionCsvParser() {
    }

    public static List<ParsedCsvRow<BulkSalaryRevisionRow>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, "newGrossSalary", SalaryRevisionCsvParser::toRow);
    }

    private static BulkSalaryRevisionRow toRow(CSVRecord record) {
        SalaryRevisionRequest request = new SalaryRevisionRequest();
        request.setNewGrossSalary(CsvRowParser.parseDecimal(record, "newGrossSalary", true));
        request.setEffectiveDate(CsvRowParser.parseDate(record, "effectiveDate", true));
        request.setReason(CsvRowParser.parseEnum(record, "reason", SalaryRevisionReason.class, true));
        request.setRemarks(CsvRowParser.get(record, "remarks"));

        request.setBasicDA(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.BASIC_DA));
        request.setHra(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.HRA));
        request.setConveyanceAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.CONVEYANCE));
        request.setEducationAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.EDUCATION));
        request.setMedicalAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.MEDICAL));
        request.setOtherAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.OTHER));

        return new BulkSalaryRevisionRow(CsvRowParser.required(record, "userId"), request);
    }
}

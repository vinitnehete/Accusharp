package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.BulkSalaryStructureRow;
import com.accusharp.hrms.dto.SalaryStructureRequest;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Turns an uploaded CSV into one salary-structure override per row, for the
 * employees whose component split genuinely differs from the company
 * {@code SalaryRule}.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code userId,
 * basicDA, hra, conveyanceAllowance, educationAllowance}. All four components
 * are required - an override replaces the whole structure, and accepting a
 * partial one would leave the unnamed components holding rule-derived values
 * that no longer follow anything.
 *
 * <p>Every row is parsed independently, same as {@link EmployeeCsvParser}.
 */
public final class SalaryStructureCsvParser {

    private SalaryStructureCsvParser() {
    }

    public static List<ParsedCsvRow<BulkSalaryStructureRow>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, SalaryColumns.BASIC_DA, SalaryStructureCsvParser::toRow);
    }

    private static BulkSalaryStructureRow toRow(CSVRecord record) {
        SalaryStructureRequest request = new SalaryStructureRequest();
        request.setBasicDA(CsvRowParser.parseDecimalAny(record, true, SalaryColumns.BASIC_DA));
        request.setHra(CsvRowParser.parseDecimalAny(record, true, SalaryColumns.HRA));
        request.setConveyanceAllowance(CsvRowParser.parseDecimalAny(record, true, SalaryColumns.CONVEYANCE));
        request.setEducationAllowance(CsvRowParser.parseDecimalAny(record, true, SalaryColumns.EDUCATION));
        request.setMedicalAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.MEDICAL));
        request.setOtherAllowance(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.OTHER));
        request.setGrossSalary(CsvRowParser.parseDecimalAny(record, false, SalaryColumns.GROSS));
        return new BulkSalaryStructureRow(CsvRowParser.required(record, "userId"), request);
    }
}

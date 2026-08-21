package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Turns an uploaded CSV into {@link EmployeeRequest} rows for bulk onboarding.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code userId,
 * employeeCode, employeeName, companyId, departmentId, designationId, categoryId,
 * supervisorUserId, joiningDate, dateOfBirth, gender, status, recordStatus, role, email, phone,
 * uanNo, esicIpNo, bankAccountNo, bankIfscNo,
 * grossSalary, pfBasic, medicalAllowance, otherAllowance, overtimeEligible,
 * basicDA, hra, conveyanceAllowance, educationAllowance}. {@code categoryId}, {@code gender}
 * and the four statutory/bank columns are all optional, same as every other non-required column.
 * {@code companyId} is ignored for a company-scoped caller - {@code
 * EmployeeController} always overwrites it with the caller's own company, the
 * same as a single create - so it only matters for a platform-level import.
 *
 * <p>The last four columns are optional and only make sense together: a
 * company migrating employees from an existing payroll system can put their
 * already-known basicDA/hra/conveyanceAllowance/educationAllowance directly
 * in the sheet instead of letting {@code SalaryRule} derive them - see
 * {@link EmployeeRequest}'s Javadoc. Leave all four blank to keep deriving
 * from the rule, as before.
 *
 * <p>Every row is parsed independently: one malformed row becomes a {@link
 * ParsedCsvRow#failed} entry rather than aborting the file, so a typo in row
 * 40 doesn't cost the 39 good rows before it.
 */
public final class EmployeeCsvParser {

    private EmployeeCsvParser() {
    }

    public static List<ParsedCsvRow<EmployeeRequest>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, "employeeCode", EmployeeCsvParser::toRequest);
    }

    private static EmployeeRequest toRequest(CSVRecord record) {
        EmployeeRequest request = new EmployeeRequest();
        request.setUserId(CsvRowParser.required(record, "userId"));
        request.setEmployeeCode(CsvRowParser.required(record, "employeeCode"));
        request.setEmployeeName(CsvRowParser.required(record, "employeeName"));
        request.setCompanyId(parseLong(record, "companyId"));
        request.setDepartmentId(parseLong(record, "departmentId"));
        request.setDesignationId(parseLong(record, "designationId"));
        request.setCategoryId(parseLong(record, "categoryId"));
        request.setSupervisorUserId(CsvRowParser.get(record, "supervisorUserId"));
        request.setJoiningDate(parseDate(record, "joiningDate"));
        request.setDateOfBirth(parseDate(record, "dateOfBirth"));
        request.setGender(parseEnum(record, "gender", Gender.class, false));
        request.setStatus(parseEnum(record, "status", EmployeeStatus.class, true));
        request.setRecordStatus(parseEnum(record, "recordStatus", RecordStatus.class, false));
        request.setRole(parseEnum(record, "role", Role.class, false));
        request.setEmail(CsvRowParser.get(record, "email"));
        request.setPhone(CsvRowParser.get(record, "phone"));
        request.setUanNo(CsvRowParser.get(record, "uanNo"));
        request.setEsicIpNo(CsvRowParser.get(record, "esicIpNo"));
        request.setBankAccountNo(CsvRowParser.get(record, "bankAccountNo"));
        request.setBankIfscNo(CsvRowParser.get(record, "bankIfscNo"));
        request.setGrossSalary(parseDecimal(record, "grossSalary", true));
        request.setPfBasic(parseDecimal(record, "pfBasic", true));
        request.setMedicalAllowance(parseDecimal(record, "medicalAllowance", true));
        request.setOtherAllowance(parseDecimal(record, "otherAllowance", true));
        request.setOvertimeEligible(parseBoolean(record, "overtimeEligible"));
        request.setBasicDA(parseDecimal(record, "basicDA", false));
        request.setHra(parseDecimal(record, "hra", false));
        request.setConveyanceAllowance(parseDecimal(record, "conveyanceAllowance", false));
        request.setEducationAllowance(parseDecimal(record, "educationAllowance", false));
        return request;
    }

    private static Long parseLong(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(CsvRowParser.stripThousandsSeparators(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a whole number, got '" + value + "'");
        }
    }

    private static BigDecimal parseDecimal(CSVRecord record, String column, boolean required) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(column + " is required");
            }
            return null;
        }
        try {
            return new BigDecimal(CsvRowParser.stripThousandsSeparators(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, got '" + value + "'");
        }
    }

    private static LocalDate parseDate(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            return null;
        }
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

    private static <E extends Enum<E>> E parseEnum(CSVRecord record, String column, Class<E> type, boolean required) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(column + " is required");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be one of " + java.util.Arrays.toString(type.getEnumConstants())
                    + ", got '" + value + "'");
        }
    }
}

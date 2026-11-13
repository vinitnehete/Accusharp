package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.RecoveryRequest;
import com.accusharp.hrms.enums.RecoveryType;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Turns an uploaded CSV into {@link RecoveryRequest} rows, for raising loans,
 * advances or fines against many employees at once - a monthly canteen-damage
 * fine list, or a festival advance run, without one form submission each.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code
 * employeeId, type, principalAmount, installmentAmount, tenureMonths,
 * startMonth, startYear, issuedOn, reason, remarks}. {@code employeeId},
 * {@code type} and {@code principalAmount} are required; everything else
 * falls back to the same defaults a single {@code POST /api/recoveries} uses
 * - an instalment derived from the tenure or the whole principal, and a start
 * period of next month for a loan and this month for everything else.
 */
public final class RecoveryCsvParser {

    private RecoveryCsvParser() {
    }

    public static List<ParsedCsvRow<RecoveryRequest>> parse(MultipartFile file) {
        return CsvRowParser.parse(file, "employeeId", RecoveryCsvParser::toRequest);
    }

    private static RecoveryRequest toRequest(CSVRecord record) {
        RecoveryRequest request = new RecoveryRequest();
        request.setEmployeeId(CsvRowParser.required(record, "employeeId"));
        request.setType(parseType(CsvRowParser.required(record, "type")));
        request.setPrincipalAmount(requiredDecimal(record, "principalAmount"));
        request.setInstallmentAmount(optionalDecimal(record, "installmentAmount"));
        request.setTenureMonths(optionalInt(record, "tenureMonths"));
        request.setStartMonth(optionalInt(record, "startMonth"));
        request.setStartYear(optionalInt(record, "startYear"));
        request.setIssuedOn(optionalDate(record, "issuedOn"));
        request.setReason(CsvRowParser.get(record, "reason"));
        request.setRemarks(CsvRowParser.get(record, "remarks"));
        return request;
    }

    private static RecoveryType parseType(String value) {
        try {
            return RecoveryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be one of LOAN, ADVANCE, FINE, OTHER, got '" + value + "'");
        }
    }

    private static BigDecimal requiredDecimal(CSVRecord record, String column) {
        BigDecimal parsed = optionalDecimal(record, column);
        if (parsed == null) {
            throw new IllegalArgumentException(column + " is required");
        }
        return parsed;
    }

    private static BigDecimal optionalDecimal(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(CsvRowParser.stripThousandsSeparators(value));
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException(column + " must be greater than zero, got '" + value + "'");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, got '" + value + "'");
        }
    }

    private static Integer optionalInt(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a whole number, got '" + value + "'");
        }
    }

    private static LocalDate optionalDate(CSVRecord record, String column) {
        return CsvRowParser.parseDate(record, column, false);
    }
}

package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.exception.BusinessRuleException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an uploaded CSV into {@link PayrollRequest} rows for running payroll
 * on a whole company in one call - one row per employee to generate, with the
 * one-off amounts (bonus, incentive, advance/loan recovery, TDS, canteen)
 * that vary month to month and can't be derived from anything already on
 * file. Everything else (attendance, LOP, PF, ESIC, PT, net pay) is still
 * computed server-side exactly as a single {@code POST /api/payroll/generate}
 * would.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code
 * employeeId, bonus, incentive, tds, advanceDeduction, loanDeduction,
 * canteen}. Only {@code employeeId} is required; every amount defaults to
 * zero when the column is absent or blank. {@code month}/{@code year} are not
 * columns - they are the single period the whole upload runs against, passed
 * once as request parameters.
 */
public final class PayrollCsvParser {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private PayrollCsvParser() {
    }

    public static List<ParsedCsvRow<PayrollRequest>> parse(MultipartFile file, int month, int year) {
        List<ParsedCsvRow<PayrollRequest>> rows = new ArrayList<>();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                try {
                    rows.add(ParsedCsvRow.ok(rowNumber, toRequest(record, month, year)));
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

    private static PayrollRequest toRequest(CSVRecord record, int month, int year) {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(required(record, "employeeId"));
        request.setMonth(month);
        request.setYear(year);
        request.setBonus(parseDecimal(record, "bonus"));
        request.setIncentive(parseDecimal(record, "incentive"));
        request.setTds(parseDecimal(record, "tds"));
        request.setAdvanceDeduction(parseDecimal(record, "advanceDeduction"));
        request.setLoanDeduction(parseDecimal(record, "loanDeduction"));
        request.setCanteen(parseDecimal(record, "canteen"));
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

    private static BigDecimal parseDecimal(CSVRecord record, String column) {
        String value = get(record, column);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() < 0) {
                throw new IllegalArgumentException(column + " cannot be negative, got '" + value + "'");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, got '" + value + "'");
        }
    }
}

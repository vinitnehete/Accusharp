package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.PayrollRequest;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

/**
 * Turns an uploaded CSV into {@link PayrollRequest} rows for running payroll
 * on a whole company in one call - one row per employee to generate, with the
 * one-off amounts (bonus, incentive, TDS, canteen) that vary month to month
 * and can't be derived from anything already on file, plus optional
 * overrides for the recovery amounts that otherwise come from the employee's
 * loan/advance/fine ledger. Everything else (attendance, LOP, PF, ESIC, PT, net pay) is still
 * computed server-side exactly as a single {@code POST /api/payroll/generate}
 * would.
 *
 * <p>Expected header (case-insensitive, order-independent): {@code
 * employeeId, bonus, incentive, tds, advanceDeduction, loanDeduction, fine,
 * otherDeduction, canteen}. Only {@code employeeId} is required.
 * {@code month}/{@code year} are not columns - they are the single period the
 * whole upload runs against, passed once as request parameters.
 *
 * <p>An absent or blank cell does <em>not</em> mean zero for the four
 * recovery columns ({@code advanceDeduction}, {@code loanDeduction},
 * {@code fine}, {@code otherDeduction}): it means "recover whatever this
 * employee's ledger schedules for the period" (see {@code PayrollRequest}).
 * That is what makes a bare {@code employeeId}-only file collect outstanding
 * loan instalments instead of the nothing it used to collect. A number in the
 * cell - {@code 0} included - overrides the schedule for that row's period,
 * so writing {@code 0} is how a file says "skip this employee's instalment
 * this month". {@code bonus}, {@code incentive}, {@code tds} and
 * {@code canteen} are not ledger-backed and still default to zero when blank.
 */
public final class PayrollCsvParser {

    private PayrollCsvParser() {
    }

    public static List<ParsedCsvRow<PayrollRequest>> parse(MultipartFile file, int month, int year) {
        return CsvRowParser.parse(file, "employeeId", record -> toRequest(record, month, year));
    }

    private static PayrollRequest toRequest(CSVRecord record, int month, int year) {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(CsvRowParser.required(record, "employeeId"));
        request.setMonth(month);
        request.setYear(year);
        request.setBonus(parseDecimal(record, "bonus"));
        request.setIncentive(parseDecimal(record, "incentive"));
        request.setTds(parseDecimal(record, "tds"));
        request.setCanteen(parseDecimal(record, "canteen"));
        request.setAdvanceDeduction(parseOverride(record, "advanceDeduction"));
        request.setLoanDeduction(parseOverride(record, "loanDeduction"));
        request.setFine(parseOverride(record, "fine"));
        request.setOtherDeduction(parseOverride(record, "otherDeduction"));
        return request;
    }

    /**
     * A ledger-backed column: blank stays null so payroll falls back to the
     * employee's scheduled instalment, rather than being overridden to zero
     * and quietly recovering nothing.
     */
    private static BigDecimal parseOverride(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        return value == null ? null : parseDecimal(record, column);
    }

    private static BigDecimal parseDecimal(CSVRecord record, String column) {
        String value = CsvRowParser.get(record, column);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal parsed = new BigDecimal(CsvRowParser.stripThousandsSeparators(value));
            if (parsed.signum() < 0) {
                throw new IllegalArgumentException(column + " cannot be negative, got '" + value + "'");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, got '" + value + "'");
        }
    }
}

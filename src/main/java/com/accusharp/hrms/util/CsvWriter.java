package com.accusharp.hrms.util;

import java.math.BigDecimal;
import java.util.List;

/**
 * Builds a CSV document a row at a time, escaping and
 * {@link CsvSanitizer#neutralizeFormula(String) neutralizing} every cell.
 *
 * <p>The report exports all needed the same three-line
 * quote/escape/neutralize dance {@code SalarySlipService.renderPeriodCsv} and
 * {@code AuditService.renderCsv} each wrote for themselves; this is that
 * logic in one place so a new export cannot accidentally ship without the
 * formula-injection guard. Those two callers are deliberately left alone -
 * they already behave correctly and rewriting them would change working code
 * for no functional gain.
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder(4096);

    public CsvWriter header(String... columns) {
        return row((Object[]) columns);
    }

    public CsvWriter row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append('\n');
        return this;
    }

    public CsvWriter row(List<?> cells) {
        return row(cells.toArray());
    }

    /** A blank separator line - used between a detail block and its totals. */
    public CsvWriter blankLine() {
        out.append('\n');
        return this;
    }

    public String build() {
        return out.toString();
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal amount) {
            return amount.toPlainString();
        }
        String cleaned = CsvSanitizer.neutralizeFormula(String.valueOf(value)).replace("\"", "\"\"");
        return cleaned.indexOf(',') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('"') >= 0
                ? "\"" + cleaned + "\""
                : cleaned;
    }
}

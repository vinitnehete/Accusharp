package com.accusharp.hrms.util;

/**
 * Neutralizes CSV formula/DDE injection (OWASP "CSV Injection"): a cell
 * value beginning with {@code =}, {@code +}, {@code -}, {@code @}, a tab, or
 * a carriage return is interpreted as a formula by Excel/Sheets/LibreOffice
 * the moment the file is opened, regardless of the value being correctly
 * CSV-quoted - CSV-escaping only protects the file format, not what a
 * spreadsheet application does with a cell's content. Every export in this
 * app writes fields a caller supplied at some point (employee name,
 * department name, audit detail, remarks) verbatim into a CSV a human then
 * opens in a spreadsheet - see SalarySlipService#renderCsv,
 * AuditService#renderCsv, EmployeeCredentialsCsvWriter#write.
 */
public final class CsvSanitizer {

    private CsvSanitizer() {
    }

    /**
     * Prefixes a leading formula-trigger character with a single quote,
     * the standard mitigation (OWASP), so the cell is forced to render as
     * text instead of being evaluated. Leaves every other value - including
     * one that merely contains, but does not start with, one of these
     * characters - untouched.
     */
    public static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}

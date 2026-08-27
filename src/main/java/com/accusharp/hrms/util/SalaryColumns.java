package com.accusharp.hrms.util;

/**
 * The spellings each salary column is accepted under in an uploaded file.
 *
 * <p>These files are normally produced by exporting an existing salary report
 * and editing it, so the headings that arrive are the report's display labels
 * ({@code Basic + DA}, {@code Con. Allow}) at least as often as the field names
 * the API uses. Failing a whole upload over a heading nobody chose is not a
 * useful failure, so both are accepted.
 *
 * <p>The first entry in each list is canonical: it is what the templates carry
 * and what an error message quotes when the column is missing.
 */
public final class SalaryColumns {

    public static final String[] BASIC_DA = {"basicDA", "Basic + DA", "Basic+DA", "Basic DA", "basic"};
    public static final String[] HRA = {"hra", "HRA"};
    public static final String[] CONVEYANCE =
            {"conveyanceAllowance", "Con. Allow", "Con Allow", "Conveyance", "conveyance"};
    public static final String[] EDUCATION =
            {"educationAllowance", "Edu. Allow", "Edu Allow", "Education", "education"};
    public static final String[] MEDICAL =
            {"medicalAllowance", "Med. Allow", "Med Allow", "Medical", "medical"};
    public static final String[] OTHER =
            {"otherAllowance", "Other Allow", "Other", "other"};
    public static final String[] GROSS =
            {"grossSalary", "Gross Salary", "Gross", "gross"};

    private SalaryColumns() {
    }
}

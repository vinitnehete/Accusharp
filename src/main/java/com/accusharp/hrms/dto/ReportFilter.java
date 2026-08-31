package com.accusharp.hrms.dto;

/**
 * Optional narrowing the payroll-audit and register reports accept on top of
 * the company scoping every {@code REPORT_READ} endpoint already applies.
 *
 * <p>Department, designation and category are the only grouping dimensions
 * this schema actually models. greytHR-style <em>location</em> and <em>cost
 * centre</em> filters have no column to filter on here - {@code Company} has
 * no location and there is no cost-centre entity - so they are deliberately
 * absent rather than faked from department.
 *
 * <p>A null field means "no narrowing on that dimension"; {@link #NONE} means
 * the whole company.
 */
public record ReportFilter(Long departmentId, Long designationId, Long categoryId) {

    public static final ReportFilter NONE = new ReportFilter(null, null, null);

    public static ReportFilter of(Long departmentId, Long designationId, Long categoryId) {
        return new ReportFilter(departmentId, designationId, categoryId);
    }

    public boolean isEmpty() {
        return departmentId == null && designationId == null && categoryId == null;
    }
}

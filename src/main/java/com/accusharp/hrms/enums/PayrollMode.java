package com.accusharp.hrms.enums;

/** Where a population's pay comes from. */
public enum PayrollMode {

    /**
     * From the attendance HR generated and reviewed - present days, loss of pay
     * and overtime. What everybody is paid by today, and what
     * {@code EmploymentType}'s pay basis then shapes.
     */
    ATTENDANCE_BASED,

    /**
     * The salary structure, every month, for the days employed. Attendance is
     * not consulted and not required, so payroll runs for someone who was never
     * tracked; a mid-month joiner or leaver is still prorated to their days, and
     * every statutory deduction applies as usual.
     */
    FIXED_MONTHLY
}

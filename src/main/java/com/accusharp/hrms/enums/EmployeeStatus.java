package com.accusharp.hrms.enums;

/**
 * Employment type. Drives payroll branching: DAY_WISE is paid against actual
 * attendance, everyone else against calendar days minus loss of pay.
 */
public enum EmployeeStatus {
    PERMANENT,
    DAY_WISE,
    CONTRACT,
    INTERN;

    public boolean isPaidPerAttendedDay() {
        return this == DAY_WISE;
    }
}

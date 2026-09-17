package com.accusharp.hrms.enums;

/** What put days onto a leave balance, as recorded in the {@code leave_credit} ledger. */
public enum LeaveCreditKind {

    /** A month's earned leave, posted when that month's payroll is generated. Period {@code yyyy-MM}. */
    MONTHLY_ACCRUAL,

    /** What a closed year carried into the next one. Period is the year closed, {@code yyyy}. */
    CARRY_FORWARD
}

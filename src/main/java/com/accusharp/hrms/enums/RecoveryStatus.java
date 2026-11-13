package com.accusharp.hrms.enums;

/**
 * Lifecycle of one {@code EmployeeRecovery}. Only {@link #ACTIVE} is ever
 * recovered by payroll; the other three are all ways of it being out of the
 * way, kept distinct because "paid off", "paused" and "written off" are
 * different answers to "why did this stop being deducted?" months later.
 */
public enum RecoveryStatus {

    /** Being recovered - payroll takes its instalment every period from the start period on. */
    ACTIVE,

    /**
     * Temporarily not recovered, balance intact. For an employee on unpaid
     * leave or in hardship: the outstanding stays exactly where it is and
     * resumes when set back to {@link #ACTIVE}, rather than being closed and
     * re-issued (which would lose the original principal and issue date).
     */
    ON_HOLD,

    /** Fully recovered or waived - outstanding is zero and nothing further is deducted. */
    CLOSED,

    /** Raised in error and reversed. Kept rather than deleted so the ledger still explains itself. */
    CANCELLED
}

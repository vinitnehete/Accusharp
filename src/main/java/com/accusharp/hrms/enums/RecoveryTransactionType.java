package com.accusharp.hrms.enums;

/**
 * One movement on a recovery's ledger. The ledger is append-only: a balance
 * is never edited in place, so "what happened to this loan" is always
 * answerable from the rows themselves.
 */
public enum RecoveryTransactionType {

    /** The recovery was created - a loan disbursed, an advance paid out, a fine levied. Increases the outstanding. */
    ISSUE,

    /** A payroll run recovered part (or all) of the outstanding. Carries the payroll row it was recovered by. */
    REPAYMENT,

    /**
     * A {@link #REPAYMENT} given back because the payroll that made it was
     * superseded by a regeneration. Payroll history is immutable, so the
     * repayment row stays and this reverses it - never a delete, and never a
     * recomputation: the amount is read back from the row being reversed.
     */
    REVERSAL,

    /** Part or all of the outstanding written off - forgiven, never to be recovered. */
    WAIVER,

    /** A manual correction to the outstanding: a wrongly-entered principal, or an amount settled outside payroll (cash, bank transfer). */
    ADJUSTMENT
}

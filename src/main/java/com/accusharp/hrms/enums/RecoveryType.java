package com.accusharp.hrms.enums;

/**
 * What kind of thing the employee owes the company. Every type shares one
 * mechanism - a balance payroll recovers over one or more periods (see
 * {@code EmployeeRecovery}) - so they are one enum rather than four parallel
 * models; only the defaults and the order they are recovered in differ.
 *
 * <p>{@link #recoveryOrder()} is the sequence payroll applies them in when
 * the month cannot afford all of them. It lives here, as data, rather than as
 * a chain of {@code if}s inside the calculation: money the company actually
 * handed over comes back before penalties it levied, and penalties before
 * miscellaneous recoveries. Whatever the month cannot cover simply stays
 * outstanding and is recovered from the next one.
 *
 * <p>{@link #isInstallmentBased()} decides only what a newly-issued recovery
 * <em>defaults</em> to when no installment is given: a loan is repaid in
 * instalments over months, whereas a fine or a one-off recovery is taken in
 * full at the next payroll that can afford it. Either default can be
 * overridden per recovery - the two are not different code paths.
 */
public enum RecoveryType {

    /** Salary advance - money paid out ahead of the payroll that earns it. */
    ADVANCE(1, false),

    /** A company loan, disbursed as a principal and repaid in monthly instalments. */
    LOAN(2, true),

    /** A penalty levied on the employee (damage, policy breach, statutory fine passed on). */
    FINE(3, false),

    /** Anything else recoverable that isn't a loan, an advance or a fine - asset recovery, notice-period shortfall, an overpayment being clawed back. */
    OTHER(4, false);

    private final int recoveryOrder;
    private final boolean installmentBased;

    RecoveryType(int recoveryOrder, boolean installmentBased) {
        this.recoveryOrder = recoveryOrder;
        this.installmentBased = installmentBased;
    }

    /** Lower runs first when a period cannot afford every outstanding recovery. */
    public int recoveryOrder() {
        return recoveryOrder;
    }

    /** True when a new recovery of this type defaults to instalments rather than recovering in full. */
    public boolean isInstallmentBased() {
        return installmentBased;
    }
}

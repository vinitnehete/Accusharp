package com.accusharp.hrms.enums;

/**
 * How an employment type's pay is derived from attendance - the single most
 * consequential thing an employment type says about somebody.
 *
 * <p>Two values, and there are genuinely only two ways this system pays anyone.
 * A client composes freely: unlimited employment types, each picking one of
 * these and carrying its own numbers. Adding a third would be a new theory of
 * pay and is deliberately a code change with a test rather than a form field -
 * a new way of computing a salary deserves a code review.
 */
public enum PayBasis {

    /**
     * Paid for the days actually attended, against a fixed monthly base
     * (26 by default). No loss-of-pay concept: you are paid what you worked.
     * The behaviour {@code DAY_WISE} has today.
     */
    PER_ATTENDED_DAY,

    /**
     * Salaried against the full calendar month - weekly offs included - reduced
     * only by whatever loss of pay the generated attendance found. The
     * behaviour {@code PERMANENT}, {@code CONTRACT} and {@code INTERN} have
     * today.
     */
    PER_CALENDAR_DAY_LESS_LOP;

    public boolean isPaidPerAttendedDay() {
        return this == PER_ATTENDED_DAY;
    }
}

package com.accusharp.hrms.enums;

/** How a leave rule gives a leave type to the population it covers. */
public enum LeaveGrant {

    /**
     * A fixed number of days for the year, set when the year's balance opens -
     * pro-rated to the months left for someone who joins during the year.
     */
    YEARLY_GRANT,

    /**
     * Earned month by month from locked attendance - see
     * {@code LeaveAccrualService}. Only valid for {@link LeaveType#EARNED_LEAVE}.
     */
    EARNED_BY_ATTENDANCE,

    /**
     * A flat number of days credited for each whole month on the books - "one
     * casual leave a month", which is how a great many companies actually run
     * CL and SL. Unlike {@link #EARNED_BY_ATTENDANCE} it does not look at
     * attendance at all; unlike {@link #YEARLY_GRANT} the year's leave is not
     * available on the first day of it.
     */
    MONTHLY_ACCRUAL,

    /**
     * None of this leave at all. The balance is simply zero, and the existing
     * balance check refuses an application exactly as it refuses one that has
     * run out - so the leave workflow itself needs no new rule.
     */
    NOT_ENTITLED
}

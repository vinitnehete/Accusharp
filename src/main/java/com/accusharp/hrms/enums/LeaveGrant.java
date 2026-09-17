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
     * {@code EarnedLeaveAccrualService}. Only valid for {@link LeaveType#EARNED_LEAVE}.
     */
    EARNED_BY_ATTENDANCE,

    /**
     * None of this leave at all. The balance is simply zero, and the existing
     * balance check refuses an application exactly as it refuses one that has
     * run out - so the leave workflow itself needs no new rule.
     */
    NOT_ENTITLED
}

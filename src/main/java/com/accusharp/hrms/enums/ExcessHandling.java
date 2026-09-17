package com.accusharp.hrms.enums;

/** What happens to unused leave above a rule's carry-forward limit when the year closes. */
public enum ExcessHandling {

    /**
     * Listed for payout - what the year close has always reported. The OSH Code
     * requires paying out EL above the carry-forward limit for employees it
     * counts as workers.
     */
    PAY_OUT,

    /**
     * Dropped. Some companies' policy - "only 45 carries, the rest lapses" - but
     * for employees the OSH Code counts as workers it may not be allowed, so it
     * is a choice made with the population in mind.
     */
    LAPSE
}

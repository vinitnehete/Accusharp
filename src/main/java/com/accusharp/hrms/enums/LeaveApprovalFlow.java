package com.accusharp.hrms.enums;

/** Who has to agree before a leave is approved, per population (see {@code WorkPolicy}). */
public enum LeaveApprovalFlow {

    /** Employee applies, their supervisor endorses, HR approves. What every company does today. */
    SUPERVISOR_THEN_HR,

    /**
     * Straight to HR: there is no endorsement step, and trying to endorse is
     * refused. For people with nobody above them to endorse - a director, a
     * head of function - where a pending endorsement would simply never come.
     */
    HR_ONLY,

    /**
     * Approved as it is applied for. The balance check still runs and the days
     * still come off the balance; nobody is asked to agree. For an owner, who
     * is not requesting a day off from their own HR.
     */
    AUTO_APPROVE
}

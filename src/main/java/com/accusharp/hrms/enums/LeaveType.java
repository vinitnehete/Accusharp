package com.accusharp.hrms.enums;

/**
 * Leave categories. LWP is unpaid and therefore never restores balance.
 *
 * <p>The yearly quota here is only the fallback for a type no leave rule covers
 * - see {@code LeaveEntitlementService}. {@link #EARNED_LEAVE} falls back to
 * zero: it is earned month by month from attendance under a rule ({@code
 * EarnedLeaveAccrualService}), plus whatever the last year carried forward -
 * never granted up front.
 */
public enum LeaveType {
    CASUAL_LEAVE(12, true),
    SICK_LEAVE(8, true),
    EARNED_LEAVE(0, true),
    LEAVE_WITHOUT_PAY(0, false);

    private final int defaultYearlyQuota;
    private final boolean paid;

    LeaveType(int defaultYearlyQuota, boolean paid) {
        this.defaultYearlyQuota = defaultYearlyQuota;
        this.paid = paid;
    }

    public int getDefaultYearlyQuota() {
        return defaultYearlyQuota;
    }

    public boolean isPaid() {
        return paid;
    }
}

package com.accusharp.hrms.enums;

/** Leave categories. LWP is unpaid and therefore never restores balance. */
public enum LeaveType {
    CASUAL_LEAVE(12, true),
    SICK_LEAVE(8, true),
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

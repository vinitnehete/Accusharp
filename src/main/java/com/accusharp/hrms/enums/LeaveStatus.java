package com.accusharp.hrms.enums;

public enum LeaveStatus {
    PENDING,
    SUPERVISOR_APPROVED,
    APPROVED,
    REJECTED,
    CANCELLED;

    /** Only a fully approved leave consumes balance and counts as paid. */
    public boolean consumesBalance() {
        return this == APPROVED;
    }

    public boolean isOpen() {
        return this == PENDING || this == SUPERVISOR_APPROVED;
    }
}

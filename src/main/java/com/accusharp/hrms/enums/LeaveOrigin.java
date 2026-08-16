package com.accusharp.hrms.enums;

/**
 * Where an approved leave came from - the leave-side analog of {@link
 * AttendanceRecordStatus}'s GENERATED/MANUAL distinction. Kept separate from
 * {@link LeaveStatus} for the same reason: provenance and current state are
 * different questions, and collapsing them loses the ability to tell "the
 * employee applied and it was approved" from "HR entered this directly"
 * once both sit at {@code APPROVED}.
 */
public enum LeaveOrigin {

    /** Went through the normal apply -> supervisor-endorse -> HR-approve chain. */
    SELF_SERVICE,

    /** HR/ADMIN entered it directly, already approved - e.g. backfilling a day that already happened. */
    HR_DIRECT
}

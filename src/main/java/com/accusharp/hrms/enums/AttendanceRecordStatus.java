package com.accusharp.hrms.enums;

/**
 * Where a stored attendance day came from - its provenance, as opposed to
 * {@link AttendanceStatus}, which says what the day <em>is</em>.
 *
 * <p>Kept separate from the lock flag on purpose: locking a day must not erase
 * the fact that a human corrected it, which is exactly the question asked when
 * a salary is disputed months later.
 */
public enum AttendanceRecordStatus {

    /** Derived from raw device punches by the generation run. */
    GENERATED,

    /** A human corrected the punches or forced the status. */
    MANUAL;

    /** Manual rows survive a regeneration unless it explicitly overwrites them. */
    public boolean survivesRegeneration() {
        return this == MANUAL;
    }
}

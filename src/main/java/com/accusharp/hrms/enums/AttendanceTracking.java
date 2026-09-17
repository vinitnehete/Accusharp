package com.accusharp.hrms.enums;

/** Whether a population is put through the attendance process at all. */
public enum AttendanceTracking {

    /** Rostered, punched, generated and reviewed - what every employee does today. */
    TRACKED,

    /**
     * Outside the process: no roster is expected, generation skips them, and no
     * day of theirs is ever marked absent. A company owner or director who
     * punches nothing but is paid every month.
     */
    NOT_TRACKED
}

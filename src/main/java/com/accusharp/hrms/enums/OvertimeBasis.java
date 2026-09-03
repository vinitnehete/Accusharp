package com.accusharp.hrms.enums;

/**
 * Which overtime figure an employment type is paid on.
 *
 * <p>The distinction is real rather than cosmetic: a day-wise worker has no
 * fixed daily shift to measure each day's overtime against, only a monthly
 * expectation, so summing per-day overtime would measure against a shift length
 * nobody agreed to.
 */
public enum OvertimeBasis {

    /**
     * The attendance engine's daily-summed figure - each day measured against
     * that day's own shift length. What everyone except {@code DAY_WISE} uses
     * today.
     */
    PER_DAY_SHIFT_EXCESS,

    /**
     * The month's total hours measured against a monthly expectation:
     * {@code totalHours - min(presentDays, payableDaysCap) * standardHoursPerDay}.
     * What {@code DAY_WISE} uses today.
     */
    MONTHLY_TOTAL_HOURS
}

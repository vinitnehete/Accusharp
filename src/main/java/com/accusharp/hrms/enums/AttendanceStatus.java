package com.accusharp.hrms.enums;

import java.math.BigDecimal;

public enum AttendanceStatus {
    PRESENT(BigDecimal.ONE),
    HALF_DAY(new BigDecimal("0.5")),
    ABSENT(BigDecimal.ZERO),
    ON_LEAVE(BigDecimal.ZERO),
    WEEKLY_OFF(BigDecimal.ZERO),
    HOLIDAY(BigDecimal.ZERO),
    INVALID_PUNCH(BigDecimal.ZERO);

    private final BigDecimal dayFraction;

    AttendanceStatus(BigDecimal dayFraction) {
        this.dayFraction = dayFraction;
    }

    /**
     * How much of a day this status is worth for payroll.
     *
     * <p>Lives on the enum rather than only in {@code
     * AttendanceCalculationService.dayFraction} because the policy engine needs
     * the same figure to enforce its central safety invariant - a day rule may
     * only ever <em>lower</em> a day's value - and two copies of "a half day is
     * 0.5" that could drift apart is not something to have on a path that
     * decides pay. {@code AttendanceCalculationService.dayFraction} delegates
     * here and stays the public entry point every existing caller uses.
     */
    public BigDecimal dayFraction() {
        return dayFraction;
    }

    /** True when this status is worth strictly less of a day than {@code other}. */
    public boolean isWorthLessThan(AttendanceStatus other) {
        return dayFraction.compareTo(other.dayFraction) < 0;
    }
}

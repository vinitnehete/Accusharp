package com.accusharp.hrms.enums;

import java.math.BigDecimal;

public enum LeaveDuration {
    FULL_DAY(new BigDecimal("1.0")),
    FIRST_HALF(new BigDecimal("0.5")),
    SECOND_HALF(new BigDecimal("0.5"));

    private final BigDecimal dayFraction;

    LeaveDuration(BigDecimal dayFraction) {
        this.dayFraction = dayFraction;
    }

    public BigDecimal getDayFraction() {
        return dayFraction;
    }
}

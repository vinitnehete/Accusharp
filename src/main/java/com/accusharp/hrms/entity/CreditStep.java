package com.accusharp.hrms.entity;

import java.math.BigDecimal;

/**
 * One step of an earned-leave rule: counting at least {@code minDays} in a month
 * earns {@code credit}. A rule's steps are tried highest first - see
 * {@code EarnedLeaveCalculator}.
 */
public record CreditStep(int minDays, BigDecimal credit) {
}

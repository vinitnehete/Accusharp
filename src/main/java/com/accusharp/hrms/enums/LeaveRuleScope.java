package com.accusharp.hrms.enums;

/**
 * Which population a leave rule covers, most specific first.
 *
 * <p>Declaration order <b>is</b> the precedence - {@code LeaveRuleResolver}
 * sorts by ordinal - and the most specific rule wins outright, never merged with
 * a broader one. Same shape as {@link RuleScope} for attendance policy, kept to
 * the two scopes leave actually needs.
 */
public enum LeaveRuleScope {

    /** {@code scopeRef} is an {@link EmployeeStatus} name - PERMANENT, DAY_WISE, CONTRACT, INTERN. */
    EMPLOYMENT_TYPE,

    /** Everybody in the company. {@code scopeRef} is {@code ANY}. */
    COMPANY
}

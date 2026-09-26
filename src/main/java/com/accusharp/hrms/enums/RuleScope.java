package com.accusharp.hrms.enums;

/**
 * Which population an attendance policy rule attaches to, most specific first.
 *
 * <p>The declaration order <b>is</b> the precedence chain - {@code
 * AttendancePolicyResolver} sorts by {@link #ordinal()}, so moving a constant
 * in this file changes which rule wins for every employee in the system. The
 * chain is total and has no ties: exactly one scope produces the rule of a
 * given type for a given employee on a given date, or none does.
 *
 * <p>Most specific wins <em>outright</em>. A {@code CATEGORY} rule does not
 * inherit unspecified parameters from the {@code COMPANY} rule above it; it
 * replaces it whole. Partial inheritance would mean the policy actually applied
 * to an employee is not any row anybody wrote, which is unexplainable by
 * construction - and an attendance policy that cannot be explained back to the
 * employee it docked is not finished.
 */
public enum RuleScope {

    /** One named employee. {@code scopeRef} is their {@code Employee.userId}. */
    EMPLOYEE(true),

    /** {@code scopeRef} is the {@code Designation.designationCode}. */
    DESIGNATION(true),

    /** {@code scopeRef} is the {@code Category.categoryCode}. */
    CATEGORY(true),

    /** {@code scopeRef} is the {@code Department.departmentCode}. */
    DEPARTMENT(true),

    /** {@code scopeRef} is an {@link EmployeeStatus} name - PERMANENT, DAY_WISE, ... */
    EMPLOYMENT_TYPE(true),

    /** Everybody in the caller's own company. {@code scopeRef} is {@link #ANY}. */
    COMPANY(false),

    /**
     * The shared fallback, on a {@code company = null} row. Ships <b>empty</b>
     * and is seeded with nothing - deliberately unlike {@code
     * AttendanceRule.defaultRule()}, because a seeded global rule would change
     * what every existing tenant is paid on the deploy that introduced it. This
     * is a hook for a future shared catalog, not a default.
     */
    GLOBAL(false);

    /**
     * The {@code scope_ref} sentinel for the two scopes that do not name
     * anything. Not null: MySQL and H2 both treat nulls in a unique index as
     * distinct, so a nullable {@code scope_ref} would let two company-scoped
     * rules of the same type and date coexist - exactly the concurrent
     * duplicate-insert race {@code SalaryRule}'s Javadoc already documents a
     * service-layer check-then-act cannot close.
     */
    public static final String ANY = "*";

    private final boolean requiresRef;

    RuleScope(boolean requiresRef) {
        this.requiresRef = requiresRef;
    }

    /** Whether {@code scopeRef} names something, or is the {@link #ANY} sentinel. */
    public boolean requiresRef() {
        return requiresRef;
    }
}

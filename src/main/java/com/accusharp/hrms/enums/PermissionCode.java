package com.accusharp.hrms.enums;

/**
 * Every grantable permission code, in one place so {@link
 * com.accusharp.hrms.config.PermissionSeeder} and each controller's
 * {@code @PreAuthorize} reference the same fixed vocabulary. The
 * {@code @PreAuthorize} SpEL string literal must match {@code name()}
 * exactly - there is no compiler check across that boundary, so a rename
 * here means finding every {@code @authz.can('...')} that used the old name.
 */
public enum PermissionCode {
    COMPANY_CREATE,
    COMPANY_READ,
    COMPANY_UPDATE,
    COMPANY_DELETE,

    DEPARTMENT_MANAGE,
    DEPARTMENT_READ,

    DESIGNATION_MANAGE,
    DESIGNATION_READ,

    CATEGORY_MANAGE,
    CATEGORY_READ,

    /**
     * Employment types and the payroll behaviour attached to them.
     *
     * <p>Separate from {@code SALARY_RULE_MANAGE} because this decides the
     * <em>shape</em> of a pay calculation, not its percentages - a different and
     * larger decision.
     *
     * <p>Granted to HR and ADMIN only, deliberately not to SUPERVISOR or
     * EMPLOYEE the way {@code CATEGORY_READ} and {@code DEPARTMENT_READ} are.
     * Those are labels; this is the rule that decides whether somebody is paid
     * per attended day or per calendar day, which belongs with
     * {@code SALARY_RULE_READ} rather than with the master-data reads it sits
     * next to.
     */
    EMPLOYMENT_TYPE_READ,
    EMPLOYMENT_TYPE_MANAGE,

    EMPLOYEE_CREATE,
    EMPLOYEE_READ,
    EMPLOYEE_UPDATE,
    EMPLOYEE_DELETE,

    SHIFT_MANAGE,
    SHIFT_READ,

    SHIFT_SCHEDULE_MANAGE,
    SHIFT_SCHEDULE_READ,

    ATTENDANCE_READ,
    ATTENDANCE_GENERATE,
    ATTENDANCE_CORRECT,
    ATTENDANCE_UNLOCK,

    ATTENDANCE_RULE_READ,
    ATTENDANCE_RULE_MANAGE,

    /**
     * The per-population attendance policy engine. Deliberately separate from
     * {@code ATTENDANCE_RULE_*}: those three thresholds apply company-wide and
     * are visible in one screen, whereas a policy rule can dock a named
     * category half a day and is a strictly larger blast radius. Granting one
     * should not silently grant the other.
     */
    ATTENDANCE_POLICY_READ,
    ATTENDANCE_POLICY_MANAGE,

    HOLIDAY_MANAGE,
    HOLIDAY_READ,

    LEAVE_APPLY,
    LEAVE_READ,
    LEAVE_SUPERVISOR_APPROVE,
    LEAVE_APPROVE,

    LEAVE_BALANCE_READ,
    LEAVE_BALANCE_MANAGE,

    SALARY_RULE_READ,
    SALARY_RULE_MANAGE,

    PAYROLL_PROCESS,
    PAYROLL_READ,

    SALARY_SLIP_READ,

    REPORT_READ,

    DASHBOARD_READ,

    AUDIT_READ,
    /** Purging old audit rows - deliberately platform-only, never granted alongside AUDIT_READ to a company role: the entity an audit trail holds accountable must never be the one who can erase it. */
    AUDIT_MANAGE,

    /** Create/edit/delete custom roles, assign their permissions, assign them to employees. ADMIN only - not HR, same trust bar as AUDIT_READ. */
    ROLE_MANAGE,
    ROLE_READ
}

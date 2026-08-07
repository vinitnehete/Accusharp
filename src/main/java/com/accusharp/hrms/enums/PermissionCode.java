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

    DASHBOARD_READ
}

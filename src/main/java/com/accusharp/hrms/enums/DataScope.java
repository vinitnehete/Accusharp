package com.accusharp.hrms.enums;

import java.util.Collection;

/**
 * How far a caller can see and act: the answer to "whose records", kept apart
 * from "what may be done", which is what every other {@link PermissionCode}
 * decides.
 *
 * <p>Declaration order is width - each value includes everything before it -
 * so {@link #widest} can pick one from a set of grants.
 *
 * <p>Scope is granted like any other permission ({@code SCOPE_DIRECT_REPORTS},
 * {@code SCOPE_ALL_REPORTS}, {@code SCOPE_COMPANY}), seeded onto the fixed roles
 * to reproduce exactly what they could always see, and grantable through a
 * custom role for anything wider - which is how a director reaches the teams
 * below their team leads without a new kind of role.
 */
public enum DataScope {

    /** Only the caller's own record. A plain EMPLOYEE. */
    SELF,

    /** The caller plus the employees who report to them directly. A SUPERVISOR. */
    DIRECT_REPORTS,

    /** The caller plus everyone below them in the reporting chain, however deep. A director. */
    ALL_REPORTS,

    /** Every employee of the company. ADMIN and HR. */
    COMPANY;

    public boolean isAtLeast(DataScope other) {
        return ordinal() >= other.ordinal();
    }

    /** The widest scope these permission codes grant, or {@link #SELF} if none do. */
    public static DataScope widest(Collection<String> permissionCodes) {
        DataScope widest = SELF;
        for (DataScope scope : values()) {
            String code = scope.permissionCode();
            if (code != null && permissionCodes.contains(code) && scope.isAtLeast(widest)) {
                widest = scope;
            }
        }
        return widest;
    }

    /**
     * What a fixed role could see before scope was grantable. Kept as a floor
     * under the granted scope rather than replaced by it, so a deployment whose
     * {@code role_permission} rows are missing the new codes still shows HR the
     * company rather than silently showing them nothing.
     */
    public static DataScope ofRole(Role role) {
        if (role == Role.ADMIN || role == Role.HR) {
            return COMPANY;
        }
        return role == Role.SUPERVISOR ? DIRECT_REPORTS : SELF;
    }

    private String permissionCode() {
        return switch (this) {
            case SELF -> null;
            case DIRECT_REPORTS -> PermissionCode.SCOPE_DIRECT_REPORTS.name();
            case ALL_REPORTS -> PermissionCode.SCOPE_ALL_REPORTS.name();
            case COMPANY -> PermissionCode.SCOPE_COMPANY.name();
        };
    }
}

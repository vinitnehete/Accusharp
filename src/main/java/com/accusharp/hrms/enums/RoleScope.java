package com.accusharp.hrms.enums;

/**
 * Which role universe a {@link com.accusharp.hrms.entity.RolePermission} row
 * applies to - {@link Role} (company) and {@link PlatformRole} (platform) are
 * separate enums with separate meanings, so a role name alone is ambiguous
 * without this.
 */
public enum RoleScope {
    COMPANY,
    PLATFORM
}

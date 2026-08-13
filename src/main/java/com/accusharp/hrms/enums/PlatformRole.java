package com.accusharp.hrms.enums;

/**
 * Access role for platform-level accounts - people who administer the
 * platform itself (company onboarding, etc.), not any one company's HR data.
 * Distinct from {@link Role}, which is company-scoped.
 */
public enum PlatformRole {
    PLATFORM_OWNER,
    PLATFORM_ADMIN
}

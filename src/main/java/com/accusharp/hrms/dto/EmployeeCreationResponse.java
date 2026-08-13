package com.accusharp.hrms.dto;

/**
 * {@code temporaryPassword} is returned exactly once, here, and never
 * logged or stored in plain text anywhere - same contract as {@link
 * CompanyOnboardingResponse#temporaryPassword()}. HR/ADMIN relays it out of
 * band; the employee changes it via {@code POST /api/auth/change-password}
 * on first login. Without this, {@code EmployeeService.create} would leave
 * every employee it creates with no password at all and no way to ever log
 * in - only {@code CompanyOnboardingService}'s first admin got one before
 * this existed.
 */
public record EmployeeCreationResponse(
        EmployeeResponse employee,
        String temporaryPassword
) {
}

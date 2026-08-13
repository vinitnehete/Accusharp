package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.Company;

/**
 * {@code temporaryPassword} is returned exactly once, here, and never logged
 * or stored in plain text anywhere - the platform operator must relay it out
 * of band. The admin should change it via {@code POST /api/auth/change-password}
 * on first login; there is no forced-change flag yet (see SECURITY.md).
 */
public record CompanyOnboardingResponse(
        Company company,
        EmployeeResponse admin,
        String temporaryPassword
) {
}

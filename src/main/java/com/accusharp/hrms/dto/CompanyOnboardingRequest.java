package com.accusharp.hrms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Platform-only: creates a company and its first ADMIN account in one step.
 * Without this, a brand-new company has nobody holding EMPLOYEE_CREATE to add
 * its staff - company-scoped ADMIN/HR only exists once an account already does.
 * The admin is a company account, not an employee, so there is no salary or
 * employee code to give it.
 */
@Data
public class CompanyOnboardingRequest {

    @NotBlank
    private String companyCode;

    @NotBlank
    private String companyName;

    private String address;

    private String phone;

    @Email
    private String companyEmail;

    @NotBlank
    private String adminUserId;

    @NotBlank
    private String adminName;

    @Email
    private String adminEmail;
}

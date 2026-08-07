package com.accusharp.hrms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Platform-only: creates a company and its first ADMIN-role employee in one
 * step. Without this, a brand-new company has zero employees and nobody
 * holding EMPLOYEE_CREATE to make the first one - company-scoped ADMIN/HR
 * only exists once an employee already does.
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
    private String adminEmployeeCode;

    @NotBlank
    private String adminName;

    @Email
    private String adminEmail;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal adminGrossSalary;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal adminPfBasic;
}

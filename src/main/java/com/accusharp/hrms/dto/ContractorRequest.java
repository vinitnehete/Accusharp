package com.accusharp.hrms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * Onboards or edits a labour contractor. {@code companyId} is deliberately
 * absent - it is always the caller's own company, resolved server-side from
 * the JWT, the same rule {@code DepartmentRequest} follows.
 */
@Data
public class ContractorRequest {

    @NotBlank
    @Size(max = 30)
    private String contractorCode;

    @NotBlank
    @Size(max = 255)
    private String contractorName;

    @Size(max = 255)
    private String contactPerson;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 500)
    private String address;

    @Size(max = 20)
    private String gstNo;

    @Size(max = 20)
    private String panNo;

    private LocalDate agreementStartDate;

    private LocalDate agreementEndDate;

    @Size(max = 500)
    private String notes;
}

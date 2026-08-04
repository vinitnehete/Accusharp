package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.RecordStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanyRequest {

    @NotBlank
    @Size(max = 30)
    private String companyCode;

    @NotBlank
    private String companyName;

    @Size(max = 500)
    private String address;

    @Size(max = 20)
    private String phone;

    @Email
    private String email;

    @NotNull
    private RecordStatus status;
}

package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DepartmentRequest {

    @NotBlank
    @Size(max = 30)
    private String departmentCode;

    @NotBlank
    private String departmentName;

    @Size(max = 500)
    private String description;
}

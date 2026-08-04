package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DesignationRequest {

    @NotBlank
    @Size(max = 30)
    private String designationCode;

    @NotBlank
    private String designationName;

    @Size(max = 500)
    private String description;
}

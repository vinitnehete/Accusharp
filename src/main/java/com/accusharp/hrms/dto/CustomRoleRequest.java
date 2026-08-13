package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomRoleRequest {

    @NotBlank
    @Size(max = 50)
    private String name;

    @Size(max = 300)
    private String description;
}

package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryRequest {

    @NotBlank
    @Size(max = 30)
    private String categoryCode;

    @NotBlank
    private String categoryName;

    @Size(max = 500)
    private String description;
}

package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

@Data
public class CustomRolePermissionsRequest {

    @NotNull
    private Set<String> permissionCodes;
}

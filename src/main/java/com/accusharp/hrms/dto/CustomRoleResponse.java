package com.accusharp.hrms.dto;

import java.util.Set;

public record CustomRoleResponse(
        Long id,
        String name,
        String description,
        Set<String> permissionCodes
) {
}

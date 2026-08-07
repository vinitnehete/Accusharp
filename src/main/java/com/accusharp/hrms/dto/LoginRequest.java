package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    @Size(max = 100)
    private String username;

    /** Bounded so an oversized value can't force a BCrypt hash over needlessly large input - cheap insurance, not a real defense on its own (body size limits already bound this). */
    @NotBlank
    @Size(max = 200)
    private String password;
}

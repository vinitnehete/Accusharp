package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank
    @Size(max = 200)
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 100, message = "must be at least 8 characters")
    // At least one letter and one digit - closes the "8 lowercase letters"
    // gap the audit's authentication review flagged. Deliberately not
    // requiring symbols/mixed-case on top - that tends to push users toward
    // predictable substitutions (password1! for password1) rather than
    // meaningfully stronger passwords.
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
            message = "must contain at least one letter and one digit")
    private String newPassword;
}

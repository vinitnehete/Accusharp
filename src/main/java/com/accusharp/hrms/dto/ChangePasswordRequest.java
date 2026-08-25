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
    @Size(min = 12, max = 200, message = "must be at least 12 characters")
    // At least one letter and one digit. Deliberately not requiring
    // symbols/mixed-case on top - that tends to push users toward predictable
    // substitutions (password1! for password1) rather than meaningfully
    // stronger passwords. Length does far more work than composition, which
    // is why the floor is 12 rather than 8 for a system holding salary and
    // bank data. The upper bound is 200, not 72, only because BCrypt itself
    // silently truncates at 72 bytes - see AuthService#changePassword, which
    // rejects anything longer rather than accepting a password whose tail is
    // ignored.
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
            message = "must contain at least one letter and one digit")
    private String newPassword;
}

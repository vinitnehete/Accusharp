package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.PrincipalType;

/** Issued on login and on refresh - the refresh token is always rotated, never reused. */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        PrincipalType principalType,
        String username,
        String role,
        boolean mustChangePassword
) {
}

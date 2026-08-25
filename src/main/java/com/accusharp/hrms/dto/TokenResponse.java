package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.PrincipalType;

/**
 * The login/refresh response body.
 *
 * <p>There is deliberately no {@code refreshToken} field. The refresh token
 * is returned only as an httpOnly cookie (see {@link
 * com.accusharp.hrms.security.RefreshTokenCookie}); keeping it out of this
 * record means no controller, mapper or future endpoint can put it into a
 * response body by accident. Internally it travels alongside this record in
 * {@link IssuedTokens}.
 *
 * <p>The access token stays in the body on purpose: the SPA holds it in
 * memory only and sends it as an {@code Authorization} header, which is what
 * keeps every business endpoint immune to CSRF.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        PrincipalType principalType,
        String username,
        String role,
        boolean mustChangePassword
) {
}

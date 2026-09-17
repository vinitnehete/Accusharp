package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.PrincipalType;

import java.util.Set;

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
 *
 * <p>{@code permissions} is everything the session may do - the base role's
 * grants plus any custom roles - so the UI decides what to show from the same
 * answer the API enforces. {@code role} stays the base role alone.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        PrincipalType principalType,
        String username,
        String role,
        boolean mustChangePassword,
        Set<String> permissions
) {
}

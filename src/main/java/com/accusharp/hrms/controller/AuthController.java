package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.ChangePasswordRequest;
import com.accusharp.hrms.dto.IssuedTokens;
import com.accusharp.hrms.dto.LoginRequest;
import com.accusharp.hrms.dto.TokenResponse;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.security.ClientAddressResolver;
import com.accusharp.hrms.security.RefreshTokenCookie;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * The four authentication endpoints, and the only place in the application
 * that touches the refresh-token cookie.
 *
 * <p>Login and refresh return the access token in the body and set the
 * refresh token as an httpOnly cookie (see {@link RefreshTokenCookie} for the
 * attributes and why each one matters). Refresh and logout read that cookie
 * rather than a request body, so a browser client never has to hold the
 * refresh token in JavaScript at all - which is the whole point: XSS in the
 * SPA can no longer steal a seven-day session.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final ClientAddressResolver clientAddressResolver;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        IssuedTokens issued = authService.login(
                request.getUsername(), request.getPassword(), clientAddressResolver.resolve(httpRequest));
        return withRefreshCookie(issued);
    }

    /**
     * Rotates the session. The incoming token comes from the cookie, never
     * from the body - a body parameter would mean JavaScript had to hold the
     * value to send it, reopening exactly the exposure the cookie closes.
     *
     * <p>A missing cookie is a 401 rather than a 400: to every caller this is
     * the same condition as an expired or revoked token - "your session is
     * gone, sign in again" - and the SPA's interceptor already treats 401 as
     * that signal. {@code RefreshTokenService} returns 401 for the revoked,
     * replayed and expired cases for the same reason.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthenticationFailedException("No active session - sign in again");
        }
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    /**
     * Revokes the token server-side and clears the cookie. Tolerates a missing
     * cookie so that signing out of an already-dead session is a no-op success
     * rather than an error the SPA has to special-case.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(IssuedTokens issued) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(issued.refreshToken()).toString())
                .body(issued.response());
    }

    /**
     * Requires a valid access token. Business endpoints are still
     * {@code permitAll} in this phase (see SecurityConfig), so this is
     * enforced explicitly here rather than by the filter chain - Phase 2
     * makes it structural via {@code @PreAuthorize} on every controller.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        if (principal == null) {
            throw new AuthenticationFailedException("Authentication is required to change your password");
        }
        authService.changePassword(principal, request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}

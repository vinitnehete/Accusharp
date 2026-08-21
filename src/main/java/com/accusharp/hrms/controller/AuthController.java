package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.ChangePasswordRequest;
import com.accusharp.hrms.dto.LoginRequest;
import com.accusharp.hrms.dto.RefreshRequest;
import com.accusharp.hrms.dto.TokenResponse;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request.getUsername(), request.getPassword(), clientAddress(httpRequest));
    }

    /**
     * The proxy/load-balancer-forwarded address when present (trusted here
     * since this app's own deployment sits behind its own reverse proxy, not
     * arbitrary internet clients who could forge the header directly against
     * this service), falling back to the direct socket address otherwise.
     */
    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.getRefreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
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

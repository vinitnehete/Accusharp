package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.TokenResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.RefreshTokenRepository;
import com.accusharp.hrms.security.JwtService;
import com.accusharp.hrms.security.LoginRateLimiter;
import com.accusharp.hrms.security.RefreshTokenService;
import com.accusharp.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Login, refresh, logout and password change for both principal realms.
 *
 * <p>Deliberately does <b>not</b> reveal which half of a login failed: an
 * unknown username, a wrong password, and a missing password hash (an
 * employee record that predates authentication and has never had a password
 * set) all produce the identical "Invalid username or password" response.
 * Only account status (locked / disabled) is reported distinctly, since that
 * is operationally useful to a real user and does not reveal a credential.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final EmployeeRepository employeeRepository;
    private final PlatformUserRepository platformUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;

    @Value("${security.max-failed-login-attempts:5}")
    private int maxFailedAttempts;

    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    /**
     * Deliberately <b>not</b> {@code @Transactional} at this level. A failed
     * attempt has to record itself (and possibly lock the account) even
     * though the very next line throws - wrapping the whole method in one
     * transaction would roll that write back along with the exception,
     * silently defeating lockout. Each {@code save()} below commits on its
     * own via Spring Data's per-method transaction instead.
     */
    /**
     * IP-throttled on top of the dispatch below: {@code assertNotThrottled}
     * rejects outright if this address has already failed too many times
     * recently, and every failure path inside {@code dispatchLogin} -
     * unknown username, wrong password, locked, disabled - uniformly throws
     * {@link AuthenticationFailedException}, so counting failures here in one
     * place catches all of them without needing a rate-limiter call at each
     * individual throw site. See {@link LoginRateLimiter}'s Javadoc for why
     * this exists alongside, not instead of, the per-account lockout below.
     */
    public TokenResponse login(String username, String rawPassword, String clientAddress) {
        loginRateLimiter.assertNotThrottled(clientAddress);
        try {
            TokenResponse response = dispatchLogin(username, rawPassword);
            loginRateLimiter.recordSuccess(clientAddress);
            return response;
        } catch (AuthenticationFailedException failure) {
            loginRateLimiter.recordFailure(clientAddress);
            throw failure;
        }
    }

    private TokenResponse dispatchLogin(String username, String rawPassword) {
        var employee = employeeRepository.findByUserId(username);
        if (employee.isPresent()) {
            return loginAsEmployee(employee.get(), rawPassword);
        }
        var platformUser = platformUserRepository.findByUsername(username);
        if (platformUser.isPresent()) {
            return loginAsPlatformUser(platformUser.get(), rawPassword);
        }
        // No such account. Same message as a wrong password - existence is not disclosed.
        log.info("auth.login.failed username={} reason=unknown-username", username);
        auditService.recordWithActor(username, null, null, "LOGIN", "Account", username,
                AuditOutcome.FAILURE, "unknown username");
        throw new AuthenticationFailedException(INVALID_CREDENTIALS);
    }

    private TokenResponse loginAsEmployee(Employee employee, String rawPassword) {
        if (!employee.isAccountEnabled()) {
            throw new AuthenticationFailedException("Account is disabled");
        }
        // Defense in depth alongside accountEnabled: EmployeeService#deactivate sets
        // both together, but a plain update() can also set recordStatus directly
        // (e.g. bulk-import, a future admin path) without going through deactivate().
        // A soft-deleted employee must never be able to authenticate either way.
        if (employee.getRecordStatus() != RecordStatus.ACTIVE) {
            throw new AuthenticationFailedException("Account is disabled");
        }
        if (employee.isAccountLocked()) {
            throw new AuthenticationFailedException(
                    "Account is locked after too many failed login attempts - contact an administrator");
        }
        if (employee.getPasswordHash() == null || !passwordEncoder.matches(rawPassword, employee.getPasswordHash())) {
            registerFailedAttempt(employee);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }

        employee.setFailedLoginAttempts(0);
        employee.setLastLoginAt(Instant.now());
        employeeRepository.save(employee);

        UserPrincipal principal = UserPrincipal.fromEmployee(employee);
        log.info("auth.login.success username={} type=EMPLOYEE", employee.getUserId());
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        auditService.recordWithActor(employee.getUserId(), PrincipalType.EMPLOYEE, companyId,
                "LOGIN", "Account", employee.getUserId(), AuditOutcome.SUCCESS, null);
        return issueTokens(principal, employee.isMustChangePassword());
    }

    private TokenResponse loginAsPlatformUser(PlatformUser user, String rawPassword) {
        if (!user.isEnabled()) {
            throw new AuthenticationFailedException("Account is disabled");
        }
        if (user.isAccountLocked()) {
            throw new AuthenticationFailedException(
                    "Account is locked after too many failed login attempts - contact an administrator");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }

        user.setFailedLoginAttempts(0);
        user.setLastLoginAt(Instant.now());
        platformUserRepository.save(user);

        UserPrincipal principal = UserPrincipal.fromPlatformUser(user);
        log.info("auth.login.success username={} type=PLATFORM", user.getUsername());
        auditService.recordWithActor(user.getUsername(), PrincipalType.PLATFORM, null,
                "LOGIN", "Account", user.getUsername(), AuditOutcome.SUCCESS, null);
        // PlatformUser has no temporary-password/forced-change concept.
        return issueTokens(principal, false);
    }

    private void registerFailedAttempt(Employee employee) {
        int attempts = employee.getFailedLoginAttempts() + 1;
        employee.setFailedLoginAttempts(attempts);
        if (attempts >= maxFailedAttempts) {
            employee.setAccountLocked(true);
            log.warn("auth.login.locked username={} attempts={}", employee.getUserId(), attempts);
        }
        employeeRepository.save(employee);
        log.info("auth.login.failed username={} reason=bad-password attempts={}", employee.getUserId(), attempts);
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        auditService.recordWithActor(employee.getUserId(), PrincipalType.EMPLOYEE, companyId,
                "LOGIN", "Account", employee.getUserId(), AuditOutcome.FAILURE,
                attempts >= maxFailedAttempts ? "bad password - account now locked" : "bad password");
    }

    private void registerFailedAttempt(PlatformUser user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= maxFailedAttempts) {
            user.setAccountLocked(true);
            log.warn("auth.login.locked username={} attempts={}", user.getUsername(), attempts);
        }
        platformUserRepository.save(user);
        log.info("auth.login.failed username={} reason=bad-password attempts={}", user.getUsername(), attempts);
        auditService.recordWithActor(user.getUsername(), PrincipalType.PLATFORM, null,
                "LOGIN", "Account", user.getUsername(), AuditOutcome.FAILURE,
                attempts >= maxFailedAttempts ? "bad password - account now locked" : "bad password");
    }

    /**
     * Not {@code @Transactional} here either, for the same reason as
     * {@link #login}: {@code refreshTokenService.consume} must commit the
     * token's one-time use independently of whatever happens afterward - if
     * the principal turns out to be disabled, the token should still be
     * spent, not silently un-revoked by a rollback.
     */
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken consumed = refreshTokenService.consume(rawRefreshToken);

        boolean mustChangePassword = false;
        UserPrincipal principal;
        switch (consumed.getPrincipalType()) {
            case EMPLOYEE -> {
                Employee employee = employeeRepository.findByUserId(consumed.getPrincipalId())
                        .filter(Employee::isAccountEnabled)
                        .filter(e -> !e.isAccountLocked())
                        .orElseThrow(() -> new AuthenticationFailedException("Account is no longer available"));
                principal = UserPrincipal.fromEmployee(employee);
                mustChangePassword = employee.isMustChangePassword();
            }
            case PLATFORM -> principal = platformUserRepository.findByUsername(consumed.getPrincipalId())
                    .filter(PlatformUser::isEnabled)
                    .filter(u -> !u.isAccountLocked())
                    .map(UserPrincipal::fromPlatformUser)
                    .orElseThrow(() -> new AuthenticationFailedException("Account is no longer available"));
            default -> throw new AuthenticationFailedException("Account is no longer available");
        }

        return issueTokens(principal, mustChangePassword);
    }

    /**
     * Attributes the audit record to whoever the <em>refresh token</em>
     * belonged to, not the current {@code SecurityContext} - logout only
     * requires a refresh token in the body, and by the time a client calls
     * it their access token may already be expired or simply never sent, so
     * there may be no authenticated principal on this request at all.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken).ifPresent(token ->
                auditService.recordWithActor(token.getPrincipalId(), token.getPrincipalType(), null,
                        "LOGOUT", "Account", token.getPrincipalId(), AuditOutcome.SUCCESS, null));
    }

    @Transactional
    public void changePassword(UserPrincipal principal, String currentPassword, String newPassword) {
        switch (principal.getType()) {
            case EMPLOYEE -> {
                Employee employee = employeeRepository.findByUserId(principal.getUsername())
                        .orElseThrow(() -> new AuthenticationFailedException("Account no longer exists"));
                if (employee.getPasswordHash() == null
                        || !passwordEncoder.matches(currentPassword, employee.getPasswordHash())) {
                    throw new AuthenticationFailedException("Current password is incorrect");
                }
                employee.setPasswordHash(passwordEncoder.encode(newPassword));
                employee.setPasswordChangedAt(Instant.now());
                employee.setMustChangePassword(false);
                employeeRepository.save(employee);
                refreshTokenRepository.revokeAllForPrincipal(PrincipalType.EMPLOYEE, employee.getUserId());
            }
            case PLATFORM -> {
                PlatformUser user = platformUserRepository.findByUsername(principal.getUsername())
                        .orElseThrow(() -> new AuthenticationFailedException("Account no longer exists"));
                if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                    throw new AuthenticationFailedException("Current password is incorrect");
                }
                user.setPasswordHash(passwordEncoder.encode(newPassword));
                user.setPasswordChangedAt(Instant.now());
                platformUserRepository.save(user);
                refreshTokenRepository.revokeAllForPrincipal(PrincipalType.PLATFORM, user.getUsername());
            }
        }
        log.info("auth.password-change username={} type={}", principal.getUsername(), principal.getType());
        auditService.record("PASSWORD_CHANGE", "Account", principal.getUsername(), AuditOutcome.SUCCESS, null);
    }

    private TokenResponse issueTokens(UserPrincipal principal, boolean mustChangePassword) {
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = refreshTokenService.issue(principal.getType(), principal.getUsername());
        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtService.getAccessTokenExpirySeconds(),
                principal.getType(), principal.getUsername(), principal.getRole(), mustChangePassword);
    }
}

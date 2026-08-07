package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.TokenResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.RefreshTokenRepository;
import com.accusharp.hrms.security.JwtService;
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
    public TokenResponse login(String username, String rawPassword) {
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
        throw new AuthenticationFailedException(INVALID_CREDENTIALS);
    }

    private TokenResponse loginAsEmployee(Employee employee, String rawPassword) {
        if (!employee.isAccountEnabled()) {
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
        return issueTokens(principal);
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
        return issueTokens(principal);
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

        UserPrincipal principal = switch (consumed.getPrincipalType()) {
            case EMPLOYEE -> employeeRepository.findByUserId(consumed.getPrincipalId())
                    .filter(Employee::isAccountEnabled)
                    .filter(e -> !e.isAccountLocked())
                    .map(UserPrincipal::fromEmployee)
                    .orElseThrow(() -> new AuthenticationFailedException("Account is no longer available"));
            case PLATFORM -> platformUserRepository.findByUsername(consumed.getPrincipalId())
                    .filter(PlatformUser::isEnabled)
                    .filter(u -> !u.isAccountLocked())
                    .map(UserPrincipal::fromPlatformUser)
                    .orElseThrow(() -> new AuthenticationFailedException("Account is no longer available"));
        };

        return issueTokens(principal);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
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
    }

    private TokenResponse issueTokens(UserPrincipal principal) {
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = refreshTokenService.issue(principal.getType(), principal.getUsername());
        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtService.getAccessTokenExpirySeconds(),
                principal.getType(), principal.getUsername(), principal.getRole());
    }
}

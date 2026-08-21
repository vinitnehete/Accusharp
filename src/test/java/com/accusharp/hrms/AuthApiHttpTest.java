package com.accusharp.hrms;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login, refresh, logout, change-password and account lockout, driven over
 * real HTTP so the JWT filter chain and {@code GlobalExceptionHandler} wiring
 * are actually exercised - a unit test against {@code AuthService} alone
 * would not catch a misconfigured {@code SecurityFilterChain}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiHttpTest {

    private static final String USER_ID = "AUTH001";
    private static final String PASSWORD = "Correct-Horse-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();

        Employee employee = Employee.builder()
                .userId(USER_ID).employeeCode("EMP-AUTH-001").employeeName("Login Test User")
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    @Test
    @DisplayName("valid credentials return an access and refresh token")
    void validLoginIssuesTokens() {
        Resp login = login(USER_ID, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().get("accessToken").asString()).isNotBlank();
        assertThat(login.body().get("refreshToken").asString()).isNotBlank();
        assertThat(login.body().get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(login.body().get("role").asString()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("wrong password and unknown username get the identical generic message")
    void invalidCredentialsDoNotRevealWhichPartWasWrong() {
        Resp wrongPassword = login(USER_ID, "not-the-password");
        Resp unknownUser = login("NO-SUCH-USER", "irrelevant");

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat(wrongPassword.body().get("message").asString())
                .isEqualTo(unknownUser.body().get("message").asString())
                .isEqualTo("Invalid username or password");
    }

    @Test
    @DisplayName("five failed attempts lock the account, even for the correct password afterwards")
    void accountLocksAfterRepeatedFailures() {
        for (int i = 0; i < 5; i++) {
            login(USER_ID, "wrong-password");
        }

        Resp lockedOut = login(USER_ID, PASSWORD);
        assertThat(lockedOut.status()).isEqualTo(401);
        assertThat(lockedOut.body().get("message").asString()).contains("locked");

        assertThat(employeeRepository.findByUserId(USER_ID).orElseThrow().isAccountLocked()).isTrue();
    }

    @Test
    @DisplayName("a disabled account cannot log in even with the correct password")
    void disabledAccountIsRejected() {
        Employee employee = employeeRepository.findByUserId(USER_ID).orElseThrow();
        employee.setAccountEnabled(false);
        employeeRepository.save(employee);

        Resp response = login(USER_ID, PASSWORD);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("message").asString()).contains("disabled");
    }

    @Test
    @DisplayName("refresh rotates the token and the old refresh token cannot be replayed")
    void refreshRotatesAndOldTokenIsRejected() {
        Resp login = login(USER_ID, PASSWORD);
        String originalRefreshToken = login.body().get("refreshToken").asString();

        Resp refreshed = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + originalRefreshToken + "\"}", null);
        assertThat(refreshed.status()).isEqualTo(200);
        String newRefreshToken = refreshed.body().get("refreshToken").asString();
        assertThat(newRefreshToken).isNotEqualTo(originalRefreshToken);

        // The consumed token cannot be replayed.
        Resp replayed = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + originalRefreshToken + "\"}", null);
        assertThat(replayed.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("replaying an already-rotated refresh token revokes the whole session family, "
            + "including the token that legitimately replaced it")
    void refreshTokenReuseRevokesEntireFamily() {
        Resp login = login(USER_ID, PASSWORD);
        String originalRefreshToken = login.body().get("refreshToken").asString();

        Resp refreshed = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + originalRefreshToken + "\"}", null);
        String newRefreshToken = refreshed.body().get("refreshToken").asString();

        // Replaying the already-consumed original is exactly the "stolen token
        // used after the legitimate client already rotated" scenario - it must
        // not just be rejected itself, it must sign out the legitimately
        // rotated session too, since there's no way to tell from here which
        // side is the attacker.
        Resp replayed = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + originalRefreshToken + "\"}", null);
        assertThat(replayed.status()).isEqualTo(400);

        Resp secondRefresh = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + newRefreshToken + "\"}", null);
        assertThat(secondRefresh.status()).isEqualTo(400);

        // The only way back in is a fresh login with the real password.
        assertThat(login(USER_ID, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("logout revokes the refresh token")
    void logoutRevokesRefreshToken() {
        Resp login = login(USER_ID, PASSWORD);
        String refreshToken = login.body().get("refreshToken").asString();

        Resp logout = send("POST", "/api/auth/logout", "{\"refreshToken\": \"" + refreshToken + "\"}", null);
        assertThat(logout.status()).isEqualTo(204);

        Resp afterLogout = send("POST", "/api/auth/refresh",
                "{\"refreshToken\": \"" + refreshToken + "\"}", null);
        assertThat(afterLogout.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("change-password requires a valid access token and the correct current password")
    void changePasswordRequiresAuthenticationAndCurrentPassword() {
        Resp noToken = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"New-Password-9\"}", null);
        assertThat(noToken.status()).isEqualTo(401);

        String accessToken = login(USER_ID, PASSWORD).body().get("accessToken").asString();

        Resp wrongCurrent = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"totally-wrong\", \"newPassword\": \"New-Password-9\"}", accessToken);
        assertThat(wrongCurrent.status()).isEqualTo(401);

        Resp changed = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"New-Password-9\"}", accessToken);
        assertThat(changed.status()).isEqualTo(204);

        assertThat(login(USER_ID, PASSWORD).status()).isEqualTo(401);
        assertThat(login(USER_ID, "New-Password-9").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a new password without both a letter and a digit is rejected")
    void newPasswordMustContainALetterAndADigit() {
        String accessToken = login(USER_ID, PASSWORD).body().get("accessToken").asString();

        Resp allLetters = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"onlyletters\"}", accessToken);
        assertThat(allLetters.status()).isEqualTo(400);

        Resp allDigits = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"12345678\"}", accessToken);
        assertThat(allDigits.status()).isEqualTo(400);

        // Untouched - the original password still works.
        assertThat(login(USER_ID, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("mustChangePassword is reported on login and cleared once the employee actually changes it")
    void mustChangePasswordIsReportedThenCleared() {
        Employee employee = employeeRepository.findByUserId(USER_ID).orElseThrow();
        employee.setMustChangePassword(true);
        employeeRepository.save(employee);

        Resp firstLogin = login(USER_ID, PASSWORD);
        assertThat(firstLogin.status()).isEqualTo(200);
        assertThat(firstLogin.body().get("mustChangePassword").asBoolean()).isTrue();

        String accessToken = firstLogin.body().get("accessToken").asString();
        Resp changed = send("POST", "/api/auth/change-password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"New-Password-9\"}", accessToken);
        assertThat(changed.status()).isEqualTo(204);

        Resp secondLogin = login(USER_ID, "New-Password-9");
        assertThat(secondLogin.status()).isEqualTo(200);
        assertThat(secondLogin.body().get("mustChangePassword").asBoolean()).isFalse();
    }

    /**
     * Every other test in this class only exercises {@code /api/auth/**},
     * which is {@code permitAll} - so none of them actually prove a business
     * endpoint is protected. This is the one that does: it hits a genuinely
     * gated endpoint with no bearer token at all, which is rejected by
     * {@code SecurityConfig}'s {@code anyRequest().authenticated()} at the
     * filter-chain level, before any controller runs.
     */
    @Test
    @DisplayName("a business endpoint with no bearer token is rejected at the filter chain, not the controller")
    void businessEndpointWithNoTokenIsRejected() {
        Resp noToken = send("GET", "/api/employees", null, null);
        assertThat(noToken.status()).isEqualTo(401);

        Resp garbageToken = send("GET", "/api/employees", null, "not-a-real-jwt");
        assertThat(garbageToken.status()).isEqualTo(401);

        String accessToken = login(USER_ID, PASSWORD).body().get("accessToken").asString();
        Resp withToken = send("GET", "/api/employees", null, accessToken);
        assertThat(withToken.status()).isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private Resp login(String username, String password) {
        String json = "{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}";
        return send("POST", "/api/auth/login", json, null);
    }

    private Resp send(String method, String path, String json, String bearerToken) {
        try {
            HttpRequest.BodyPublisher payload = json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port() + path))
                    .header("Content-Type", "application/json")
                    .method(method, payload);
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? null
                    : objectMapper.readTree(response.body());
            return new Resp(response.statusCode(), body);
        } catch (Exception ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
    }

    private String port() {
        return environment.getProperty("local.server.port");
    }
}

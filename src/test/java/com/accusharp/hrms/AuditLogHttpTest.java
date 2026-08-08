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
 * Proves the audit trail is actually live, not just wired: a failed then a
 * successful login both leave a row, readable by ADMIN but not by HR - the
 * one deliberate asymmetry in an otherwise identical ADMIN/HR permission set
 * (see PermissionSeeder).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogHttpTest {

    private static final String PASSWORD = "Audit-Test-1";

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
        saveEmployee("ADMIN01", Role.ADMIN);
        saveEmployee("HR01", Role.HR);
    }

    @Test
    @DisplayName("failed and successful logins are both audited, readable by ADMIN, forbidden to HR")
    void loginAttemptsAreAudited() {
        send("POST", "/api/auth/login", "{\"username\": \"ADMIN01\", \"password\": \"wrong\"}", null);
        String adminToken = login("ADMIN01", PASSWORD);

        Resp asAdmin = send("GET", "/api/audit-logs?limit=10", null, adminToken);
        assertThat(asAdmin.status()).isEqualTo(200);
        assertThat(asAdmin.body().size()).isGreaterThanOrEqualTo(2);
        assertThat(asAdmin.body().get(0).get("action").asString()).isEqualTo("LOGIN");

        String hrToken = login("HR01", PASSWORD);
        Resp asHr = send("GET", "/api/audit-logs?limit=10", null, hrToken);
        assertThat(asHr.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a salary rule change is audited - Phase 10's extended coverage beyond login/employee/payroll")
    void salaryRuleChangeIsAudited() {
        String adminToken = login("ADMIN01", PASSWORD);

        String update = """
                {"basicDaPercent": 55, "hraPercent": 40, "conveyancePercent": 10, "educationPercent": 10,
                 "pfPercent": 12, "esicPercent": 0.75, "esicWageCeiling": 21000,
                 "ptUpperThreshold": 10001, "ptUpperAmount": 200, "ptLowerThreshold": 7501, "ptLowerAmount": 175,
                 "dayWiseDaysInMonth": 26, "standardHoursPerDay": 8, "overtimeRateMultiplier": 1.0}""";
        Resp updated = send("PUT", "/api/salary-rules", update, adminToken);
        assertThat(updated.status()).isEqualTo(200);

        Resp logs = send("GET", "/api/audit-logs?limit=10", null, adminToken);
        assertThat(logs.status()).isEqualTo(200);
        boolean found = false;
        for (int i = 0; i < logs.body().size(); i++) {
            if ("SALARY_RULE_UPDATE".equals(logs.body().get(i).get("action").asString())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private String login(String userId, String password) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + userId + "\", \"password\": \"" + password + "\"}", null);
        return response.body().get("accessToken").asString();
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

    private void saveEmployee(String userId, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRuleForCompany(null));
        employeeRepository.save(employee);
    }
}

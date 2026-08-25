package com.accusharp.hrms;

import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.enums.PlatformRole;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform-only company onboarding: a company and its first ADMIN employee, created together. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompanyOnboardingHttpTest {

    private static final String PLATFORM_PASSWORD = "Platform-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformUserRepository platformUserRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.accusharp.hrms.repository.AuditLogRepository auditLogRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Deletes in FK-dependency order (children before parents). Spring Boot
     * Test caches and reuses one {@code ApplicationContext} - and one H2
     * instance - across test classes with an identical configuration, so
     * leftover rows from another test class's company (e.g. a holiday
     * created in {@code TenantIsolationHttpTest}) are exactly as real a
     * foreign-key hazard here as leftover rows from this class's own
     * previous run.
     */
    @BeforeEach
    void setUp() {
        holidayRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
        platformUserRepository.deleteAll();

        platformUserRepository.save(PlatformUser.builder()
                .username("owner1").passwordHash(passwordEncoder.encode(PLATFORM_PASSWORD))
                .email("owner1@accusharp.example").role(PlatformRole.PLATFORM_OWNER)
                .enabled(true).accountLocked(false).failedLoginAttempts(0)
                .createdAt(Instant.now()).build());
    }

    @Test
    @DisplayName("onboarding creates the company and a working ADMIN login for it")
    void onboardingCreatesCompanyAndWorkingAdmin() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);

        String body = """
                {"companyCode": "NEWCO", "companyName": "New Co Industries", "companyEmail": "hr@newco.example",
                 "adminUserId": "NEWCO-ADMIN", "adminEmployeeCode": "NC-ADMIN-1", "adminName": "First Admin",
                 "adminEmail": "admin@newco.example", "adminGrossSalary": 50000, "adminPfBasic": 15000}""";
        Resp onboarded = send("POST", "/api/companies/onboard", body, platformToken);
        assertThat(onboarded.status()).isEqualTo(201);
        assertThat(onboarded.body().get("company").get("companyCode").asString()).isEqualTo("NEWCO");
        assertThat(onboarded.body().get("admin").get("role").asString()).isEqualTo("ADMIN");
        String temporaryPassword = onboarded.body().get("temporaryPassword").asString();
        assertThat(temporaryPassword).isNotBlank();

        // The generated admin can actually log in and use their own permissions.
        Resp adminLogin = send("POST", "/api/auth/login",
                "{\"username\": \"NEWCO-ADMIN\", \"password\": \"" + temporaryPassword + "\"}", null);
        assertThat(adminLogin.status()).isEqualTo(200);
        String adminToken = adminLogin.body().get("accessToken").asString();

        Resp createsOwnEmployee = send("POST", "/api/employees", """
                {"userId": "NEWCO-EMP1", "employeeCode": "NC-EMP-1", "employeeName": "First Hire",
                 "status": "PERMANENT", "role": "EMPLOYEE",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0}""",
                adminToken);
        assertThat(createsOwnEmployee.status()).isEqualTo(201);
        assertThat(createsOwnEmployee.body().get("employee").get("companyName").asString())
                .isEqualTo("New Co Industries");

        // The new hire is not left with no password - they can actually log in too.
        String newHirePassword = createsOwnEmployee.body().get("temporaryPassword").asString();
        assertThat(newHirePassword).isNotBlank();
        Resp newHireLogin = send("POST", "/api/auth/login",
                "{\"username\": \"NEWCO-EMP1\", \"password\": \"" + newHirePassword + "\"}", null);
        assertThat(newHireLogin.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("HR/ADMIN can reset an employee's password - the old one stops working, the new one logs in")
    void adminCanResetAnEmployeesPassword() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        String setupBody = """
                {"companyCode": "RESETCO", "companyName": "Reset Co", "companyEmail": "hr@resetco.example",
                 "adminUserId": "RESETCO-ADMIN", "adminEmployeeCode": "RC-ADMIN-1", "adminName": "Admin",
                 "adminEmail": "admin@resetco.example", "adminGrossSalary": 40000, "adminPfBasic": 12000}""";
        Resp onboarded = send("POST", "/api/companies/onboard", setupBody, platformToken);
        String adminToken = login("RESETCO-ADMIN", onboarded.body().get("temporaryPassword").asString());

        Resp created = send("POST", "/api/employees", """
                {"userId": "RESETCO-EMP1", "employeeCode": "RC-EMP-1", "employeeName": "Someone",
                 "status": "PERMANENT", "role": "EMPLOYEE",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0}""",
                adminToken);
        String originalPassword = created.body().get("temporaryPassword").asString();
        long employeeId = created.body().get("employee").get("id").asLong();

        Resp reset = send("POST", "/api/employees/" + employeeId + "/reset-password", null, adminToken);
        assertThat(reset.status()).isEqualTo(200);
        String newPassword = reset.body().get("temporaryPassword").asString();
        assertThat(newPassword).isNotBlank().isNotEqualTo(originalPassword);

        Resp loginWithOld = send("POST", "/api/auth/login",
                "{\"username\": \"RESETCO-EMP1\", \"password\": \"" + originalPassword + "\"}", null);
        assertThat(loginWithOld.status()).isEqualTo(401);

        Resp loginWithNew = send("POST", "/api/auth/login",
                "{\"username\": \"RESETCO-EMP1\", \"password\": \"" + newPassword + "\"}", null);
        assertThat(loginWithNew.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a company-scoped ADMIN cannot onboard a new company")
    void onboardingIsPlatformOnly() {
        // Onboard once to get a company-scoped admin token, then try onboarding again with it.
        login("owner1", PLATFORM_PASSWORD);
        String setupBody = """
                {"companyCode": "OTHERCO", "companyName": "Other Co", "companyEmail": "hr@otherco.example",
                 "adminUserId": "OTHERCO-ADMIN", "adminEmployeeCode": "OC-ADMIN-1", "adminName": "Admin",
                 "adminEmail": "admin@otherco.example", "adminGrossSalary": 40000, "adminPfBasic": 12000}""";
        Resp onboarded = send("POST", "/api/companies/onboard", setupBody, login("owner1", PLATFORM_PASSWORD));
        String temporaryPassword = onboarded.body().get("temporaryPassword").asString();
        String companyAdminToken = login("OTHERCO-ADMIN", temporaryPassword);

        Resp forbidden = send("POST", "/api/companies/onboard", setupBody, companyAdminToken);
        assertThat(forbidden.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("deactivating a company via PUT leaves a COMPANY_STATUS_CHANGE audit row")
    void companyStatusChangeIsAudited() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        String setupBody = """
                {"companyCode": "AUDITCO", "companyName": "Audit Co", "companyEmail": "hr@auditco.example",
                 "adminUserId": "AUDITCO-ADMIN", "adminEmployeeCode": "AC-ADMIN-1", "adminName": "Admin",
                 "adminEmail": "admin@auditco.example", "adminGrossSalary": 40000, "adminPfBasic": 12000}""";
        Resp onboarded = send("POST", "/api/companies/onboard", setupBody, platformToken);
        long companyId = onboarded.body().get("company").get("id").asLong();

        Resp deactivated = send("PUT", "/api/companies/" + companyId, """
                {"companyCode": "AUDITCO", "companyName": "Audit Co", "status": "INACTIVE"}""", platformToken);
        assertThat(deactivated.status()).isEqualTo(200);
        assertThat(deactivated.body().get("status").asString()).isEqualTo("INACTIVE");

        Resp logs = send("GET", "/api/audit-logs?limit=20", null, platformToken);
        assertThat(logs.status()).isEqualTo(200);
        boolean found = false;
        for (int i = 0; i < logs.body().size(); i++) {
            if ("COMPANY_STATUS_CHANGE".equals(logs.body().get(i).get("action").asString())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("a platform owner can purge genuinely old audit rows, and the purge itself is recorded")
    void platformCanPurgeAuditLog() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        String setupBody = """
                {"companyCode": "PURGECO", "companyName": "Purge Co", "companyEmail": "hr@purgeco.example",
                 "adminUserId": "PURGECO-ADMIN", "adminEmployeeCode": "PC-ADMIN-1", "adminName": "Admin",
                 "adminEmail": "admin@purgeco.example", "adminGrossSalary": 40000, "adminPfBasic": 12000}""";
        send("POST", "/api/companies/onboard", setupBody, platformToken);

        // Backdate the trail this test just generated. Previously this purged
        // with a cutoff of 2099-01-01 - "delete everything" - which the
        // retention floor now refuses outright, and rightly so: erasing
        // history written moments ago is the abuse the guard exists to stop,
        // not the maintenance task this test is about. Ageing the rows keeps
        // the test exercising the real path.
        auditLogRepository.findAll().forEach(entry -> {
            entry.setTimestamp(Instant.now().minus(Duration.ofDays(400)));
            auditLogRepository.save(entry);
        });

        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        Resp purge = send("DELETE",
                "/api/audit-logs?beforeDate=" + cutoff + "&confirmExportedUpTo=" + cutoff,
                null, platformToken);
        assertThat(purge.status()).isEqualTo(200);
        assertThat(purge.body().get("deleted").asLong()).isGreaterThan(0);

        // Everything before the cutoff is gone; the purge event itself, written after, is not.
        Resp logs = send("GET", "/api/audit-logs?limit=5", null, platformToken);
        assertThat(logs.status()).isEqualTo(200);
        assertThat(logs.body().get(0).get("action").asString()).isEqualTo("AUDIT_LOG_PURGE");
    }

    @Test
    @DisplayName("purging recent audit history is refused even by a platform owner")
    void recentAuditHistoryCannotBePurged() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        Resp purge = send("DELETE",
                "/api/audit-logs?beforeDate=" + tomorrow + "&confirmExportedUpTo=" + tomorrow,
                null, platformToken);
        assertThat(purge.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("purging without the export confirmation is refused")
    void purgeWithoutExportConfirmationIsRefused() {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        Resp purge = send("DELETE", "/api/audit-logs?beforeDate=2020-01-01", null, platformToken);
        // Missing required parameter is a client error, not a 500.
        assertThat(purge.status()).isEqualTo(400);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private String login(String username, String password) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}", null);
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed for " + username + ": " + response.body());
        }
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
}

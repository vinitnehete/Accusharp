package com.accusharp.hrms;

import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.enums.PlatformRole;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.ContractorRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A platform account's name is not available to anybody's employee.
 *
 * <p>Login looks in the employee table first, so an employee whose userId matched a
 * platform username would answer that name's login instead of the platform account -
 * and lock the platform owner out. A company admin (or anyone allowed to add staff) must
 * not be able to do that, by any route that gives an employee row its userId.
 *
 * <p>The refusal is the plain "already exists" answer, word for word, so it never says
 * that the name belongs to a platform account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservedUserIdHttpTest {

    private static final String PLATFORM_PASSWORD = "Platform-Reserved-1";
    private static final String NAME = "reserved_owner";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformUserRepository platformUserRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ContractorRepository contractorRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String platformToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clean();
        platformUserRepository.save(PlatformUser.builder()
                .username(NAME).passwordHash(passwordEncoder.encode(PLATFORM_PASSWORD))
                .email("owner@accusharp.example").role(PlatformRole.PLATFORM_OWNER)
                .enabled(true).accountLocked(false).failedLoginAttempts(0)
                .createdAt(Instant.now()).build());
        platformToken = login(NAME, PLATFORM_PASSWORD);

        Resp onboarded = send("POST", "/api/companies/onboard", """
                {"companyCode": "RESV-CO", "companyName": "Reserved Co", "adminUserId": "RESV-ADMIN",
                 "adminName": "Owner"}""", platformToken);
        adminToken = login("RESV-ADMIN", onboarded.body().get("temporaryPassword").asString());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("no employee can be created with a platform account's name, in any letter case")
    void noEmployeeCanTakeAPlatformName() {
        for (String name : List.of(NAME, NAME.toUpperCase())) {
            Resp created = send("POST", "/api/employees", employee(name), adminToken);

            assertThat(created.status()).as(name).isEqualTo(409);
            // Word for word the answer for any taken userId - it must not reveal what holds the name.
            assertThat(created.body().get("message").asString()).isEqualTo("Employee already exists with userId " + name);
            assertThat(employeeRepository.findByUserId(name)).isEmpty();
        }
        // ...and the platform owner can still sign in.
        assertThat(login(NAME, PLATFORM_PASSWORD)).isNotBlank();
    }

    @Test
    @DisplayName("an existing employee cannot be renamed onto one either")
    void noEmployeeCanBeRenamedOntoAPlatformName() {
        long id = send("POST", "/api/employees", employee("RESV-EMP1"), adminToken)
                .body().get("employee").get("id").asLong();

        Resp renamed = send("PUT", "/api/employees/" + id, employee(NAME.toUpperCase()), adminToken);

        assertThat(renamed.status()).isEqualTo(409);
        assertThat(employeeRepository.findById(id).orElseThrow().getUserId()).isEqualTo("RESV-EMP1");
        assertThat(login(NAME, PLATFORM_PASSWORD)).isNotBlank();
    }

    @Test
    @DisplayName("nor a contractor's worker - they have no login, but the employee row would still answer the name")
    void noContractorWorkerCanTakeAPlatformName() {
        long contractorId = send("POST", "/api/contractors",
                "{\"contractorCode\": \"RESV-C\", \"contractorName\": \"Reserved Manpower\"}", adminToken)
                .body().get("id").asLong();
        String worker = "{\"userId\": \"%s\", \"employeeCode\": \"%s\", \"employeeName\": \"Worker\"}";

        Resp created = send("POST", "/api/contractors/" + contractorId + "/employees",
                worker.formatted(NAME, "RW-1"), adminToken);
        assertThat(created.status()).isEqualTo(409);

        long workerId = send("POST", "/api/contractors/" + contractorId + "/employees",
                worker.formatted("RESV-W1", "RW-2"), adminToken).body().get("id").asLong();
        Resp renamed = send("PUT", "/api/contractors/employees/" + workerId,
                worker.formatted(NAME.toUpperCase(), "RW-2"), adminToken);

        assertThat(renamed.status()).isEqualTo(409);
        assertThat(employeeRepository.findByUserId("RESV-W1")).isPresent();
        assertThat(login(NAME, PLATFORM_PASSWORD)).isNotBlank();
    }

    @Test
    @DisplayName("a company cannot be onboarded with an admin named like a platform account")
    void noAdminCanTakeAPlatformName() {
        Resp onboarded = send("POST", "/api/companies/onboard", """
                {"companyCode": "RESV-CO2", "companyName": "Second Co", "adminUserId": "%s",
                 "adminName": "Owner"}""".formatted(NAME.toUpperCase()), platformToken);

        assertThat(onboarded.status()).isEqualTo(409);
        assertThat(companyRepository.existsByCompanyCode("RESV-CO2")).isFalse();
    }

    // ---- helpers -------------------------------------------------------------------

    private String employee(String userId) {
        return """
                {"userId": "%s", "employeeName": "%s Name", "status": "PERMANENT",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 0, "otherAllowance": 0}"""
                .formatted(userId, userId);
    }

    private record Resp(int status, JsonNode body) {
    }

    private String login(String userId, String password) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + userId + "\", \"password\": \"" + password + "\"}", null);
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed for " + userId + ": " + response.body());
        }
        return response.body().get("accessToken").asString();
    }

    private Resp send(String method, String path, String json, String bearerToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                    .header("Content-Type", "application/json")
                    .method(method, json == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json));
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

    private void clean() {
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        contractorRepository.deleteAll();
        holidayRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        companyRepository.deleteAll();
        platformUserRepository.deleteAll();
    }
}

package com.accusharp.hrms;

import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.enums.PlatformRole;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.CustomRolePermissionRepository;
import com.accusharp.hrms.repository.CustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamic role/permission management (Phase 10): a company ADMIN creates a
 * named custom role, grants it permissions, and assigns it to an employee -
 * additive on top of that employee's fixed {@code Role}. Proves the whole
 * chain end to end over real HTTP: a plain EMPLOYEE who could not call
 * {@code REPORT_READ}-gated endpoints gains that ability purely through a
 * custom-role assignment, and loses it again on unassignment - with no
 * change to their JWT/base role at any point.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomRoleHttpTest {

    private static final String PLATFORM_PASSWORD = "Platform-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformUserRepository platformUserRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private CustomRoleRepository customRoleRepository;
    @Autowired private CustomRolePermissionRepository customRolePermissionRepository;
    @Autowired private EmployeeCustomRoleRepository employeeCustomRoleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        auditLogRepository.deleteAll();
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
    @DisplayName("assigning a custom role grants its permissions; unassigning takes them away - base Role never changes")
    void customRoleGrantsAndRevokesPermission() {
        String adminToken = onboardCompanyAndGetAdminToken("ROLECO", "roleco.example");

        Resp employee = send("POST", "/api/employees", """
                {"userId": "ROLECO-EMP1", "employeeCode": "RC-EMP-1", "employeeName": "Plain Employee",
                 "status": "PERMANENT", "role": "EMPLOYEE",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0}""",
                adminToken);
        String employeePassword = employee.body().get("temporaryPassword").asString();
        String employeeToken = login("ROLECO-EMP1", employeePassword);

        // A plain EMPLOYEE cannot read reports - REPORT_READ is HR/ADMIN/SUPERVISOR only.
        Resp beforeGrant = send("GET", "/api/reports/employees", null, employeeToken);
        assertThat(beforeGrant.status()).isEqualTo(403);

        Resp createdRole = send("POST", "/api/roles", """
                {"name": "Report Viewer", "description": "Extra reporting access for one employee"}""",
                adminToken);
        assertThat(createdRole.status()).isEqualTo(201);
        long roleId = createdRole.body().get("id").asLong();

        Resp granted = send("PUT", "/api/roles/" + roleId + "/permissions",
                "{\"permissionCodes\": [\"REPORT_READ\"]}", adminToken);
        assertThat(granted.status()).isEqualTo(200);
        assertThat(granted.body().get("permissionCodes").toString()).contains("REPORT_READ");

        Resp assigned = send("POST", "/api/roles/" + roleId + "/employees/ROLECO-EMP1", null, adminToken);
        assertThat(assigned.status()).isEqualTo(204);

        // Same employee, same token, same base Role - now succeeds purely via the custom role.
        Resp afterGrant = send("GET", "/api/reports/employees", null, employeeToken);
        assertThat(afterGrant.status()).isEqualTo(200);

        Resp unassigned = send("DELETE", "/api/roles/" + roleId + "/employees/ROLECO-EMP1", null, adminToken);
        assertThat(unassigned.status()).isEqualTo(204);

        Resp afterRevoke = send("GET", "/api/reports/employees", null, employeeToken);
        assertThat(afterRevoke.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a custom role can never be granted a platform-only permission")
    void platformOnlyPermissionIsRejected() {
        String adminToken = onboardCompanyAndGetAdminToken("GUARDCO", "guardco.example");

        Resp role = send("POST", "/api/roles", """
                {"name": "Overreaching Role"}""", adminToken);
        long roleId = role.body().get("id").asLong();

        Resp rejected = send("PUT", "/api/roles/" + roleId + "/permissions",
                "{\"permissionCodes\": [\"COMPANY_DELETE\"]}", adminToken);
        assertThat(rejected.status()).isEqualTo(400);

        Resp rejectedAuditManage = send("PUT", "/api/roles/" + roleId + "/permissions",
                "{\"permissionCodes\": [\"AUDIT_MANAGE\"]}", adminToken);
        assertThat(rejectedAuditManage.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("HR (not ADMIN) cannot manage custom roles")
    void roleManagementIsAdminOnly() {
        String adminToken = onboardCompanyAndGetAdminToken("HRCO", "hrco.example");
        Resp hrEmployee = send("POST", "/api/employees", """
                {"userId": "HRCO-HR1", "employeeCode": "HC-HR-1", "employeeName": "HR Person",
                 "status": "PERMANENT", "role": "HR",
                 "grossSalary": 30000, "pfBasic": 10000, "medicalAllowance": 1000, "otherAllowance": 0}""",
                adminToken);
        String hrToken = login("HRCO-HR1", hrEmployee.body().get("temporaryPassword").asString());

        Resp forbidden = send("POST", "/api/roles", "{\"name\": \"Should Not Work\"}", hrToken);
        assertThat(forbidden.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Company A's ADMIN cannot manage Company B's custom role or assign it to Company B's employee")
    void customRoleManagementIsTenantIsolated() {
        String adminAToken = onboardCompanyAndGetAdminToken("TENA", "tena.example");
        String adminBToken = onboardCompanyAndGetAdminToken("TENB", "tenb.example");

        send("POST", "/api/employees", """
                {"userId": "TENB-EMP1", "employeeCode": "TB-EMP-1", "employeeName": "B Employee",
                 "status": "PERMANENT", "role": "EMPLOYEE",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0}""",
                adminBToken);

        Resp roleB = send("POST", "/api/roles", "{\"name\": \"B Only Role\"}", adminBToken);
        long roleBId = roleB.body().get("id").asLong();

        // A cannot read, edit or delete B's role.
        assertThat(send("GET", "/api/roles/" + roleBId, null, adminAToken).status()).isEqualTo(404);
        assertThat(send("PUT", "/api/roles/" + roleBId + "/permissions",
                "{\"permissionCodes\": [\"REPORT_READ\"]}", adminAToken).status()).isEqualTo(404);
        // A cannot assign B's role to B's employee either, even naming both correctly.
        assertThat(send("POST", "/api/roles/" + roleBId + "/employees/TENB-EMP1", null, adminAToken).status())
                .isEqualTo(404);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private String onboardCompanyAndGetAdminToken(String companyCode, String domain) {
        String platformToken = login("owner1", PLATFORM_PASSWORD);
        String body = """
                {"companyCode": "%s", "companyName": "%s Corp", "companyEmail": "hr@%s",
                 "adminUserId": "%s-ADMIN", "adminEmployeeCode": "%s-ADM-1", "adminName": "Admin",
                 "adminEmail": "admin@%s", "adminGrossSalary": 50000, "adminPfBasic": 15000}
                """.formatted(companyCode, companyCode, domain, companyCode, companyCode, domain);
        Resp onboarded = send("POST", "/api/companies/onboard", body, platformToken);
        String temporaryPassword = onboarded.body().get("temporaryPassword").asString();
        return login(companyCode + "-ADMIN", temporaryPassword);
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

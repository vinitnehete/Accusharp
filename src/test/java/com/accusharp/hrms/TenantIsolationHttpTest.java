package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
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
 * Two companies, two HR users, over real HTTP - proving Company A's HR
 * cannot reach Company B's data by id, no matter how the request is shaped.
 * This is the literal scenario the tenant-isolation work exists for: "GET
 * /api/employees/500 must not return that employee" when 500 belongs to a
 * different company.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantIsolationHttpTest {

    private static final String PASSWORD = "Tenant-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company companyA;
    private Company companyB;
    private Employee hrA;
    private Employee employeeB;
    private String hrAToken;

    @BeforeEach
    void setUp() {
        salaryRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        companyA = companyRepository.save(Company.builder()
                .companyCode("TENANT-A").companyName("Tenant A Industries")
                .status(RecordStatus.ACTIVE).build());
        companyB = companyRepository.save(Company.builder()
                .companyCode("TENANT-B").companyName("Tenant B Industries")
                .status(RecordStatus.ACTIVE).build());

        hrA = saveEmployee("HRA001", "Tenant A HR", Role.HR, companyA);
        employeeB = saveEmployee("EMPB001", "Tenant B Worker", Role.EMPLOYEE, companyB);

        hrAToken = login("HRA001");
    }

    @Test
    @DisplayName("Company A's HR gets 404, not the record, for Company B's employee by id or userId")
    void employeeCrossTenantReadIsRejected() {
        Resp byId = send("GET", "/api/employees/" + employeeB.getId(), null, hrAToken);
        assertThat(byId.status()).isEqualTo(404);

        Resp byUserId = send("GET", "/api/employees/by-user-id/EMPB001", null, hrAToken);
        assertThat(byUserId.status()).isEqualTo(404);

        // Sanity check: the same HR can read their own company's employee fine.
        Resp ownCompany = send("GET", "/api/employees/by-user-id/HRA001", null, hrAToken);
        assertThat(ownCompany.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Company A's HR gets 404 for Company B's payroll history by employee id")
    void payrollCrossTenantReadIsRejected() {
        Resp history = send("GET", "/api/payroll/employee/EMPB001", null, hrAToken);
        assertThat(history.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("an employee create request cannot plant the new hire in another company")
    void employeeCreateIgnoresClientSuppliedCompanyId() {
        String body = """
                {
                  "userId": "EMPA002", "employeeCode": "EMP-A-002", "employeeName": "Sneaky Hire",
                  "companyId": %d, "status": "PERMANENT", "role": "EMPLOYEE",
                  "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0
                }
                """.formatted(companyB.getId());

        Resp created = send("POST", "/api/employees", body, hrAToken);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("companyName").asString()).isEqualTo(companyA.getCompanyName());
    }

    @Test
    @DisplayName("GET /api/companies only shows the caller's own company, and /{id} 404s for another")
    void companyListAndGetAreScoped() {
        Resp list = send("GET", "/api/companies", null, hrAToken);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(1);
        assertThat(list.body().get(0).get("companyCode").asString()).isEqualTo("TENANT-A");

        Resp otherCompany = send("GET", "/api/companies/" + companyB.getId(), null, hrAToken);
        assertThat(otherCompany.status()).isEqualTo(404);

        Resp ownCompany = send("GET", "/api/companies/" + companyA.getId(), null, hrAToken);
        assertThat(ownCompany.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("updating the salary rule creates a company-specific row and leaves other companies on the default")
    void salaryRuleUpdateIsPerCompany() {
        Resp before = send("GET", "/api/salary-rules", null, hrAToken);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().get("basicDaPercent").asDouble()).isEqualTo(50.0);

        String update = """
                {"basicDaPercent": 60, "hraPercent": 40, "conveyancePercent": 10, "educationPercent": 10,
                 "pfPercent": 12, "esicPercent": 0.75, "esicWageCeiling": 21000,
                 "ptUpperThreshold": 10001, "ptUpperAmount": 200, "ptLowerThreshold": 7501, "ptLowerAmount": 175,
                 "dayWiseDaysInMonth": 26, "standardHoursPerDay": 8, "overtimeRateMultiplier": 1.0}""";
        Resp updated = send("PUT", "/api/salary-rules", update, hrAToken);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("basicDaPercent").asDouble()).isEqualTo(60.0);

        // The global default (what an as-yet-uncustomized company still reads) is untouched.
        assertThat(salaryRuleService.getActiveRuleForCompany(null).getBasicDaPercent())
                .isEqualByComparingTo(new BigDecimal("50"));
        // Company B never customized its own rule, so it still resolves to the untouched default too.
        assertThat(salaryRuleService.getActiveRuleForCompany(companyB).getBasicDaPercent())
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private String login(String userId) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + userId + "\", \"password\": \"" + PASSWORD + "\"}", null);
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed for " + userId + ": " + response.body());
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

    private Employee saveEmployee(String userId, String name, Role role, Company company) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(name)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRuleForCompany(company));
        return employeeRepository.save(employee);
    }
}

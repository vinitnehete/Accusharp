package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.PayrollRepository;
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
 * {@code month} and {@code year} arrive as bare {@code @RequestParam int}s on
 * the payroll, report and salary-slip endpoints, so nothing about the type
 * system stops {@code ?month=99}.
 *
 * <p>Before the {@code @Min}/{@code @Max} constraints existed, an out-of-range
 * month travelled all the way into {@code PayrollService.build}, where
 * {@code YearMonth.of} threw {@code DateTimeException} - a {@code
 * RuntimeException} that is <em>not</em> an {@code IllegalArgumentException},
 * so {@code GlobalExceptionHandler} did not map it and the caller got a 500
 * (or, on the bulk paths, a raw {@code java.time} message per row). An
 * ordinary client mistake reported as a server fault.
 *
 * <p>These tests pin both halves of that fix: the rejection is a 400 naming
 * the offending parameter, and a valid period is completely unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestParameterValidationHttpTest {

    private static final String PASSWORD = "Param-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("PARAM-CO").companyName("Param Co").status(RecordStatus.ACTIVE).build());
        saveEmployee("PARAMHR1", company);
        hrToken = login("PARAMHR1");
    }

    @Test
    @DisplayName("a month outside 1-12 is a 400 naming the parameter, not a 500")
    void outOfRangeMonthIsRejected() {
        Resp tooHigh = send("GET", "/api/payroll?month=99&year=2026");
        assertThat(tooHigh.status()).isEqualTo(400);
        assertThat(tooHigh.body().get("message").asString()).contains("month");

        Resp zero = send("GET", "/api/payroll?month=0&year=2026");
        assertThat(zero.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the generate path - where YearMonth.of used to throw - rejects the bad month up front")
    void generateAllRejectsOutOfRangeMonthBeforeItReachesTheCalculation() {
        Resp response = send("POST", "/api/payroll/generate-all?month=13&year=2026");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("message").asString()).contains("month");
    }

    @Test
    @DisplayName("an implausible year is rejected the same way")
    void outOfRangeYearIsRejected() {
        assertThat(send("GET", "/api/payroll?month=6&year=1").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("reports and salary slips enforce the same bounds")
    void otherControllersEnforceTheSameBounds() {
        assertThat(send("GET", "/api/reports/payroll?month=13&year=2026").status()).isEqualTo(400);
        assertThat(send("GET", "/api/salary-slips?month=13&year=2026").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a valid period is unaffected - the constraints reject nothing a real client sends")
    void validPeriodStillWorks() {
        Resp payroll = send("GET", "/api/payroll?month=6&year=2026");
        assertThat(payroll.status()).isEqualTo(200);

        assertThat(send("GET", "/api/reports/payroll?month=1&year=2026").status()).isEqualTo(200);
        assertThat(send("GET", "/api/reports/payroll?month=12&year=2026").status()).isEqualTo(200);
        assertThat(send("GET", "/api/salary-slips?month=6&year=2026").status()).isEqualTo(200);
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

    private Resp send(String method, String path) {
        return send(method, path, null, hrToken);
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

    private void saveEmployee(String userId, Company company) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.HR)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee,
                salaryRuleService.getActiveRuleForCompany(company));
        employeeRepository.save(employee);
    }
}

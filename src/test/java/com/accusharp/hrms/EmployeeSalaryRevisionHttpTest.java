package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
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
 * A salary hike/promotion/correction must update the employee's live gross
 * salary and structure, and must leave behind an immutable history row -
 * the audit trail {@code SalaryRule}'s own Javadoc flags as missing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeSalaryRevisionHttpTest {

    private static final String PASSWORD = "Revision-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company company;
    private Employee worker;
    private String hrToken;

    @BeforeEach
    void setUp() {
        salaryRevisionRepository.deleteAll();
        holidayRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        departmentRepository.deleteAll();
        payrollRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("REV-CO").companyName("Revision Co")
                .status(RecordStatus.ACTIVE).build());

        saveEmployee("HR001", "HR Admin", Role.HR);
        worker = saveEmployee("EMP001", "Worker One", Role.EMPLOYEE);

        hrToken = login("HR001");
    }

    @Test
    @DisplayName("a hike updates gross salary, re-derives the structure, and is recorded in history")
    void hikeUpdatesGrossAndRederivesStructure() {
        // Baseline: gross 20000 -> basicDA 10000 (50%) -> hra 4000 (40% of basicDA).
        Resp revised = send("POST", "/api/employees/" + worker.getId() + "/salary-revision", """
                {"newGrossSalary": 24000, "effectiveDate": "2026-09-01", "reason": "ANNUAL_INCREMENT",
                 "remarks": "Yearly appraisal"}""",
                hrToken);
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("grossSalary").asDouble()).isEqualTo(24000.0);
        assertThat(revised.body().get("basicDA").asDouble()).isEqualTo(12000.0); // 50% of 24000
        assertThat(revised.body().get("hra").asDouble()).isEqualTo(4800.0); // 40% of 12000

        Resp history = send("GET", "/api/employees/" + worker.getId() + "/salary-revisions", null, hrToken);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body()).hasSize(1);
        JsonNode entry = history.body().get(0);
        assertThat(entry.get("previousGrossSalary").asDouble()).isEqualTo(20000.0);
        assertThat(entry.get("newGrossSalary").asDouble()).isEqualTo(24000.0);
        assertThat(entry.get("hikePercent").asDouble()).isEqualTo(20.0); // (24000-20000)/20000 * 100
        assertThat(entry.get("reason").asString()).isEqualTo("ANNUAL_INCREMENT");
        assertThat(entry.get("revisedBy").asString()).isEqualTo("HR001");
    }

    @Test
    @DisplayName("a hike for an overridden employee without a replacement structure is rejected")
    void hikeOnOverriddenEmployeeWithoutStructureRejected() {
        send("PUT", "/api/employees/" + worker.getId() + "/salary-structure", """
                {"basicDA": 11000, "hra": 5000, "conveyanceAllowance": 900, "educationAllowance": 900}""",
                hrToken);

        Resp revised = send("POST", "/api/employees/" + worker.getId() + "/salary-revision", """
                {"newGrossSalary": 24000, "effectiveDate": "2026-09-01", "reason": "PROMOTION"}""",
                hrToken);
        assertThat(revised.status()).isEqualTo(400);

        // Neither gross salary nor history should have changed.
        Resp unchanged = send("GET", "/api/employees/" + worker.getId(), null, hrToken);
        assertThat(unchanged.body().get("grossSalary").asDouble()).isEqualTo(20000.0);
        Resp history = send("GET", "/api/employees/" + worker.getId() + "/salary-revisions", null, hrToken);
        assertThat(history.body()).isEmpty();
    }

    @Test
    @DisplayName("a hike for an overridden employee with a replacement structure applies both")
    void hikeOnOverriddenEmployeeWithStructureApplies() {
        send("PUT", "/api/employees/" + worker.getId() + "/salary-structure", """
                {"basicDA": 11000, "hra": 5000, "conveyanceAllowance": 900, "educationAllowance": 900}""",
                hrToken);

        Resp revised = send("POST", "/api/employees/" + worker.getId() + "/salary-revision", """
                {"newGrossSalary": 24000, "effectiveDate": "2026-09-01", "reason": "PROMOTION",
                 "basicDA": 13000, "hra": 5800, "conveyanceAllowance": 1100, "educationAllowance": 1100}""",
                hrToken);
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("grossSalary").asDouble()).isEqualTo(24000.0);
        assertThat(revised.body().get("basicDA").asDouble()).isEqualTo(13000.0);
        assertThat(revised.body().get("salaryStructureOverridden").asBoolean()).isTrue();
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

    private Employee saveEmployee(String userId, String name, Role role) {
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

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
 * Manually overriding an employee's basicDA/hra/conveyance/education must
 * stick across plain updates and rule changes, and must be undoable with a
 * regenerate call that goes back to what {@code SalaryRule} derives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeSalaryStructureHttpTest {

    private static final String PASSWORD = "Structure-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
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
                .companyCode("STRUCT-CO").companyName("Structure Co")
                .status(RecordStatus.ACTIVE).build());

        saveEmployee("HR001", "HR Admin", Role.HR);
        worker = saveEmployee("EMP001", "Worker One", Role.EMPLOYEE);

        hrToken = login("HR001");
    }

    @Test
    @DisplayName("manually overriding the structure sticks, and regenerate restores rule-derived values")
    void overrideThenRegenerate() {
        // Rule-derived baseline: 50% basicDA, 40%/10%/10% of it.
        Resp before = send("GET", "/api/employees/" + worker.getId(), null, hrToken);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().get("basicDA").asDouble()).isEqualTo(10000.0);
        assertThat(before.body().get("salaryStructureOverridden").asBoolean()).isFalse();

        Resp overridden = send("PUT", "/api/employees/" + worker.getId() + "/salary-structure", """
                {"basicDA": 11000, "hra": 5000, "conveyanceAllowance": 900, "educationAllowance": 900}""",
                hrToken);
        assertThat(overridden.status()).isEqualTo(200);
        assertThat(overridden.body().get("basicDA").asDouble()).isEqualTo(11000.0);
        assertThat(overridden.body().get("hra").asDouble()).isEqualTo(5000.0);
        assertThat(overridden.body().get("salaryStructureOverridden").asBoolean()).isTrue();

        // A plain employee update must not silently discard the override.
        Resp updated = send("PUT", "/api/employees/" + worker.getId(), """
                {"userId": "EMP001", "employeeCode": "EMP-EMP001", "employeeName": "Worker One",
                 "status": "PERMANENT", "role": "EMPLOYEE",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1500, "otherAllowance": 0}""",
                hrToken);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("basicDA").asDouble()).isEqualTo(11000.0);
        assertThat(updated.body().get("salaryStructureOverridden").asBoolean()).isTrue();
        // Gross wage still refreshes with the new medical allowance: 11000+5000+900+900+1500.
        assertThat(updated.body().get("grossSalaryWage").asDouble()).isEqualTo(19300.0);

        // Regenerate drops the override and goes back to what the rule derives.
        Resp regenerated = send("POST", "/api/employees/" + worker.getId() + "/salary-structure/regenerate",
                null, hrToken);
        assertThat(regenerated.status()).isEqualTo(200);
        assertThat(regenerated.body().get("salaryStructureOverridden").asBoolean()).isFalse();
        assertThat(regenerated.body().get("basicDA").asDouble()).isEqualTo(10000.0);
        assertThat(regenerated.body().get("hra").asDouble()).isEqualTo(4000.0);
    }

    @Test
    @DisplayName("a salary rule change is not reflected until regenerate-all is called, "
            + "and an overridden employee is left alone by it")
    void ruleChangeRequiresRegenerateAll() {
        Resp overridden = send("PUT", "/api/employees/" + worker.getId() + "/salary-structure", """
                {"basicDA": 11000, "hra": 5000, "conveyanceAllowance": 900, "educationAllowance": 900}""",
                hrToken);
        assertThat(overridden.status()).isEqualTo(200);

        Employee secondWorker = saveEmployee("EMP002", "Worker Two", Role.EMPLOYEE);

        // Raise basicDA from 50% to 60%.
        send("PUT", "/api/salary-rules", """
                {"basicDaPercent": 60, "basicDaMinimumThreshold": 0, "hraPercent": 40, "conveyancePercent": 10,
                 "educationPercent": 10, "pfPercent": 12, "esicPercent": 0.75, "esicWageCeiling": 21000,
                 "ptUpperThreshold": 10001, "ptUpperAmount": 200, "ptLowerThreshold": 7501, "ptLowerAmount": 175,
                 "dayWiseDaysInMonth": 26, "standardHoursPerDay": 8, "overtimeRateMultiplier": 1.0,
                 "mlwfAmount": 0}""", hrToken);

        // Not reflected yet - stale until regenerated.
        Resp staleSecond = send("GET", "/api/employees/" + secondWorker.getId(), null, hrToken);
        assertThat(staleSecond.body().get("basicDA").asDouble()).isEqualTo(10000.0);

        Resp bulk = send("POST", "/api/employees/salary-structure/regenerate-all", null, hrToken);
        assertThat(bulk.status()).isEqualTo(200);
        assertThat(bulk.body().get("regenerated").asInt()).isEqualTo(2); // HR001 + EMP002, not the overridden EMP001

        Resp freshSecond = send("GET", "/api/employees/" + secondWorker.getId(), null, hrToken);
        assertThat(freshSecond.body().get("basicDA").asDouble()).isEqualTo(12000.0); // 60% of 20000

        // The overridden employee's manual values survived the bulk regenerate untouched.
        Resp stillOverridden = send("GET", "/api/employees/" + worker.getId(), null, hrToken);
        assertThat(stillOverridden.body().get("basicDA").asDouble()).isEqualTo(11000.0);
        assertThat(stillOverridden.body().get("salaryStructureOverridden").asBoolean()).isTrue();
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

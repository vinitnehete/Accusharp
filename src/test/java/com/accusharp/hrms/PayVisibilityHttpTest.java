package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.*;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pay - salary, bank and statutory numbers, payroll, salary slips and every
 * report carrying money - is the employee's own, or for whoever holds
 * {@code PAY_READ}. A supervisor's data scope reaches their team's records,
 * never what the team is paid.
 *
 * <p>SUP supervises EMP; both are paid a fixed monthly salary for May 2031 so
 * payroll runs without attendance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayVisibilityHttpTest {

    private static final String PASSWORD = "Pay-Visibility-1";
    private static final String MAY = "month=5&year=2031";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private WorkPolicyRepository workPolicyRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private SalaryStructureRevisionRepository salaryStructureRevisionRepository;
    @Autowired private EmployeeCustomRoleRepository employeeCustomRoleRepository;
    @Autowired private CustomRolePermissionRepository customRolePermissionRepository;
    @Autowired private CustomRoleRepository customRoleRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private long supId;
    private long empId;
    private String adminToken;
    private String hrToken;
    private String supToken;
    private String empToken;

    @BeforeEach
    void setUp() {
        clean();
        Company company = companyRepository.save(Company.builder()
                .companyCode("PAYVIS").companyName("Pay Visibility Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("PVADMIN", Role.ADMIN, company, null);
        saveEmployee("PVHR", Role.HR, company, null);
        Employee sup = saveEmployee("PVSUP", Role.SUPERVISOR, company, null);
        Employee emp = saveEmployee("PVEMP", Role.EMPLOYEE, company, sup);
        supId = sup.getId();
        empId = emp.getId();

        adminToken = login("PVADMIN");
        hrToken = login("PVHR");
        supToken = login("PVSUP");
        empToken = login("PVEMP");

        assertThat(send("POST", "/api/work-policies", """
                {"scope": "COMPANY", "attendanceTracking": "NOT_TRACKED", "payrollMode": "FIXED_MONTHLY",
                 "effectiveFrom": "2031-05-01"}""", hrToken).status()).isEqualTo(201);
        for (String userId : List.of("PVSUP", "PVEMP")) {
            assertThat(send("POST", "/api/payroll/generate",
                    "{\"employeeId\": \"" + userId + "\", \"month\": 5, \"year\": 2031, \"generatedBy\": \"x\"}",
                    hrToken).status()).isEqualTo(201);
        }
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- employee records --------------------------------------------------

    @Test
    @DisplayName("a supervisor opens a team member's record without their salary, bank or statutory numbers")
    void teamRecordComesWithoutPay() {
        JsonNode emp = send("GET", "/api/employees/" + empId, null, supToken).body();

        assertThat(emp.get("userId").asString()).isEqualTo("PVEMP");
        assertThat(emp.get("employeeName").asString()).isEqualTo("PVEMP Name");
        for (String field : List.of("grossSalary", "basicDA", "hra", "pfBasic", "grossSalaryWage",
                "bankAccountNo", "bankIfscNo", "uanNo", "esicIpNo")) {
            assertThat(emp.get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    @DisplayName("everyone still sees their own pay - the supervisor's own record keeps it")
    void ownRecordKeepsPay() {
        assertThat(send("GET", "/api/employees/" + supId, null, supToken).body().get("grossSalary").asDouble())
                .isEqualTo(31000.0);
    }

    @Test
    @DisplayName("the team list and the employee list carry no team pay")
    void listsCarryNoTeamPay() {
        JsonNode team = send("GET", "/api/employees/PVSUP/team", null, supToken).body();
        JsonNode all = send("GET", "/api/employees", null, supToken).body();

        assertThat(team).isNotEmpty();
        team.forEach(member -> assertThat(member.get("grossSalary").isNull()).isTrue());
        all.forEach(person -> assertThat(person.get("grossSalary").isNull())
                .as(person.get("userId").asString())
                .isEqualTo(!person.get("userId").asString().equals("PVSUP")));
    }

    @Test
    @DisplayName("salary history is refused for anyone else - and structure history no longer leaks to any employee")
    void salaryHistoryIsOwnOnly() {
        assertThat(send("GET", "/api/employees/" + empId + "/salary-revisions", null, supToken).status())
                .isEqualTo(403);
        assertThat(send("GET", "/api/employees/" + empId + "/salary-structure-revisions", null, supToken).status())
                .isEqualTo(403);
        // Previously unscoped: a plain employee could read a colleague's structure history.
        assertThat(send("GET", "/api/employees/" + supId + "/salary-structure-revisions", null, empToken).status())
                .isEqualTo(403);
        assertThat(send("GET", "/api/employees/" + supId + "/salary-revisions", null, supToken).status())
                .isEqualTo(200);
    }

    // ---- payroll and salary slips ------------------------------------------

    @Test
    @DisplayName("a supervisor gets their own payroll and slip, and is refused their team's")
    void payrollAndSlipsAreOwnOnly() {
        assertThat(send("GET", "/api/payroll/employee/PVEMP/period?" + MAY, null, supToken).status()).isEqualTo(403);
        assertThat(send("GET", "/api/salary-slips/PVEMP?" + MAY, null, supToken).status()).isEqualTo(403);
        assertThat(send("GET", "/api/salary-slips/PVSUP?" + MAY, null, supToken).status()).isEqualTo(200);

        assertThat(userIds(send("GET", "/api/payroll?" + MAY, null, supToken).body(), "employeeId"))
                .containsExactly("PVSUP");
        assertThat(userIds(send("GET", "/api/salary-slips?" + MAY, null, supToken).body(), "employeeId"))
                .containsExactly("PVSUP");
    }

    // ---- reports ----------------------------------------------------------------

    @Test
    @DisplayName("reports with money in them are refused; attendance reports still work")
    void payReportsAreRefused() {
        for (String path : List.of("/api/reports/payroll?" + MAY, "/api/reports/statutory/pf?" + MAY,
                "/api/reports/payroll/register?" + MAY, "/api/reports/payroll/bank-transfer?" + MAY,
                "/api/reports/attendance/overtime-register?" + MAY)) {
            assertThat(send("GET", path, null, supToken).status()).as(path).isEqualTo(403);
        }
        assertThat(send("GET", "/api/reports/attendance/monthly?month=2031-05", null, supToken).status())
                .isEqualTo(200);

        JsonNode employees = send("GET", "/api/reports/employees", null, supToken).body();
        employees.forEach(person -> assertThat(person.get("grossSalary").isNull())
                .isEqualTo(!person.get("userId").asString().equals("PVSUP")));
    }

    // ---- who may see pay ---------------------------------------------------------

    @Test
    @DisplayName("HR sees everyone's pay, as before")
    void hrSeesPay() {
        assertThat(send("GET", "/api/employees/" + empId, null, hrToken).body().get("grossSalary").asDouble())
                .isEqualTo(31000.0);
        assertThat(send("GET", "/api/salary-slips/PVEMP?" + MAY, null, hrToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/reports/payroll?" + MAY, null, hrToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a custom role can grant PAY_READ - a director who should see pay")
    void payReadCanBeGranted() {
        long roleId = send("POST", "/api/roles", "{\"name\": \"Pay reader\"}", adminToken).body().get("id").asLong();
        assertThat(send("PUT", "/api/roles/" + roleId + "/permissions", "{\"permissionCodes\": [\"PAY_READ\"]}",
                adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + roleId + "/employees/PVSUP", null, adminToken).status())
                .isEqualTo(204);

        assertThat(send("GET", "/api/employees/" + empId, null, supToken).body().get("grossSalary").asDouble())
                .isEqualTo(31000.0);
        assertThat(send("GET", "/api/salary-slips/PVEMP?" + MAY, null, supToken).status()).isEqualTo(200);
    }

    // ---- helpers -------------------------------------------------------------------

    private List<String> userIds(JsonNode rows, String field) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get(field).asString()));
        return ids;
    }

    private record Resp(int status, JsonNode body) {
    }

    private Employee saveEmployee(String userId, Role role, Company company, Employee supervisor) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("C-" + userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("31000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .bankAccountNo("123456789012").bankIfscNo("SBIN0000001")
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRuleForCompany(company));
        return employeeRepository.save(employee);
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
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        payrollRepository.deleteAll();
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        salaryRevisionRepository.deleteAll();
        salaryStructureRevisionRepository.deleteAll();
        workPolicyRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}

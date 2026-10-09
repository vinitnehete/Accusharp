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
 * The company's ADMIN login is a company account, not a member of its staff - and
 * the admin is the one person nobody else may change.
 *
 * <p>Part 1: the admin has no salary and no workspace, so the employee processes
 * (directory, payroll, attendance, leave) never include them. Part 2: whoever holds
 * {@code EMPLOYEE_UPDATE} - HR, or anyone given it through a custom role - may edit
 * everybody's record except their own and the admin's; those two are the admin's.
 *
 * <p>ACHR and ACEMP are paid a fixed monthly salary for May 2031 so payroll runs
 * without attendance. ACADMIN is saved the way onboarding now creates it: no pay at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompanyAccountHttpTest {

    private static final String PASSWORD = "Company-Account-1";
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
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private long adminId;
    private long hrId;
    private long supId;
    private long empId;
    private String adminToken;
    private String hrToken;
    private String supToken;

    @BeforeEach
    void setUp() {
        clean();
        Company company = companyRepository.save(Company.builder()
                .companyCode("ACCT-CO").companyName("Account Co").status(RecordStatus.ACTIVE).build());

        adminId = saveEmployee("ACADMIN", Role.ADMIN, company, null).getId();
        hrId = saveEmployee("ACHR", Role.HR, company, null).getId();
        Employee sup = saveEmployee("ACSUP", Role.SUPERVISOR, company, null);
        supId = sup.getId();
        empId = saveEmployee("ACEMP", Role.EMPLOYEE, company, sup).getId();

        adminToken = login("ACADMIN");
        hrToken = login("ACHR");
        supToken = login("ACSUP");

        assertThat(send("POST", "/api/work-policies", """
                {"scope": "COMPANY", "attendanceTracking": "NOT_TRACKED", "payrollMode": "FIXED_MONTHLY",
                 "effectiveFrom": "2031-05-01"}""", hrToken).status()).isEqualTo(201);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- part 1: the admin is a company account, not an employee ------------------

    @Test
    @DisplayName("the admin is not in the employee directory - for HR or for the admin themselves")
    void adminIsNotInTheDirectory() {
        for (String token : List.of(hrToken, adminToken)) {
            assertThat(userIds(send("GET", "/api/employees", null, token).body(), "userId"))
                    .containsExactlyInAnyOrder("ACHR", "ACSUP", "ACEMP");
        }
    }

    @Test
    @DisplayName("whole-company payroll pays the staff and leaves the admin out")
    void adminIsNotPaid() {
        JsonNode run = send("POST", "/api/payroll/generate-all?" + MAY, null, adminToken).body();

        assertThat(userIds(run.get("succeeded"), "employeeId")).containsExactlyInAnyOrder("ACHR", "ACSUP", "ACEMP");
        assertThat(run.get("errors")).isEmpty();
    }

    @Test
    @DisplayName("payroll for the admin by name is refused, not run")
    void payrollForTheAdminIsRefused() {
        Resp generated = send("POST", "/api/payroll/generate",
                "{\"employeeId\": \"ACADMIN\", \"month\": 5, \"year\": 2031, \"generatedBy\": \"x\"}", hrToken);

        assertThat(generated.status()).isEqualTo(400);
        assertThat(payrollRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("attendance is never generated for the admin, even when named")
    void attendanceForTheAdminIsRefused() {
        Resp generated = send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-05\", \"userIds\": [\"ACADMIN\"], \"generatedBy\": \"x\"}", hrToken);

        assertThat(generated.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the admin has no leave to apply for - they are not an employee")
    void adminCannotApplyForLeave() {
        Resp applied = send("POST", "/api/leaves", leave("ACADMIN"), adminToken);

        assertThat(applied.status()).isEqualTo(400);
        // Nor can HR enter one for them directly.
        assertThat(send("POST", "/api/leaves/hr-create", leave("ACADMIN"), hrToken).status()).isEqualTo(400);
        assertThat(send("POST", "/api/leaves", leave("ACEMP"), hrToken).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("the admin has no leave balance to show or set - nothing is created for them")
    void adminHasNoLeaveBalance() {
        assertThat(send("GET", "/api/leave-balances/ACADMIN?year=2031", null, adminToken).body()).isEmpty();
        assertThat(send("PUT", "/api/leave-balances/ACADMIN?year=2031&leaveType=CASUAL_LEAVE&quota=30", null, hrToken)
                .status()).isEqualTo(400);

        assertThat(leaveBalanceRepository.count()).isZero();
    }

    @Test
    @DisplayName("the admin still runs the company: creates staff and reads the directory")
    void adminKeepsFullAccess() {
        Resp created = send("POST", "/api/employees", """
                {"userId": "ACNEW", "employeeName": "New Hire", "status": "PERMANENT",
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 0, "otherAllowance": 0}""", adminToken);

        assertThat(created.status()).isEqualTo(201);
        assertThat(send("GET", "/api/employees", null, adminToken).body()).hasSize(4);
    }

    // ---- part 2: your own record, and the admin's, are the admin's to change --------

    @Test
    @DisplayName("HR cannot raise their own salary")
    void hrCannotEditOwnRecord() {
        Resp updated = send("PUT", "/api/employees/" + hrId, record("ACHR", 300000), hrToken);

        assertThat(updated.status()).isEqualTo(403);
        assertThat(employeeRepository.findById(hrId).orElseThrow().getGrossSalary()).isEqualByComparingTo("31000");
    }

    @Test
    @DisplayName("nor use any other write on their own record - revision, structure, reset, deactivate, supervisor")
    void hrCannotUseAnyWriteOnOwnRecord() {
        String own = "/api/employees/" + hrId;
        assertThat(send("POST", own + "/salary-revision", """
                {"newGrossSalary": 300000, "effectiveDate": "%s", "reason": "OTHER"}"""
                .formatted(LocalDate.now().plusDays(1)), hrToken).status()).isEqualTo(403);
        assertThat(send("PUT", own + "/salary-structure",
                "{\"basicDA\": 1, \"hra\": 1, \"conveyanceAllowance\": 1, \"educationAllowance\": 1}", hrToken)
                .status()).isEqualTo(403);
        assertThat(send("POST", own + "/salary-structure/regenerate", null, hrToken).status()).isEqualTo(403);
        assertThat(send("POST", own + "/reset-password", null, hrToken).status()).isEqualTo(403);
        assertThat(send("POST", own + "/unlock", null, hrToken).status()).isEqualTo(403);
        assertThat(send("PATCH", "/api/employees/ACHR/supervisor?supervisorUserId=ACSUP", null, hrToken).status())
                .isEqualTo(403);
        assertThat(send("DELETE", own, null, hrToken).status()).isEqualTo(403);

        Employee hr = employeeRepository.findById(hrId).orElseThrow();
        assertThat(hr.getGrossSalary()).isEqualByComparingTo("31000");
        assertThat(hr.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
        assertThat(hr.getSupervisor()).isNull();
    }

    @Test
    @DisplayName("HR still edits everyone else's record as before")
    void hrStillEditsColleagues() {
        Resp updated = send("PUT", "/api/employees/" + empId, record("ACEMP", 42000), hrToken);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("grossSalary").asDouble()).isEqualTo(42000.0);
    }

    @Test
    @DisplayName("the admin can edit HR's record - it is theirs to change")
    void adminEditsHr() {
        Resp updated = send("PUT", "/api/employees/" + hrId, record("ACHR", 45000), adminToken);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(employeeRepository.findById(hrId).orElseThrow().getGrossSalary()).isEqualByComparingTo("45000");
    }

    @Test
    @DisplayName("the same holds for anyone given EMPLOYEE_UPDATE through a custom role")
    void customRoleHolderCannotEditSelf() {
        long roleId = send("POST", "/api/roles", "{\"name\": \"People editor\"}", adminToken).body().get("id").asLong();
        assertThat(send("PUT", "/api/roles/" + roleId + "/permissions", "{\"permissionCodes\": [\"EMPLOYEE_UPDATE\"]}",
                adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + roleId + "/employees/ACSUP", null, adminToken).status())
                .isEqualTo(204);

        assertThat(send("PUT", "/api/employees/" + supId, record("ACSUP", 300000), supToken).status())
                .isEqualTo(403);
        assertThat(send("PUT", "/api/employees/" + empId, record("ACEMP", 33000), supToken).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("HR cannot reset, demote or deactivate the admin")
    void hrCannotTouchTheAdmin() {
        String admin = "/api/employees/" + adminId;

        assertThat(send("POST", admin + "/reset-password", null, hrToken).status()).isEqualTo(403);
        assertThat(send("PUT", admin, record("ACADMIN", 1000).replace("\"status\"", "\"role\": \"EMPLOYEE\", \"status\""),
                hrToken).status()).isEqualTo(403);
        assertThat(send("DELETE", admin, null, hrToken).status()).isEqualTo(403);

        Employee stored = employeeRepository.findById(adminId).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.ADMIN);
        assertThat(stored.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
    }

    // ---- part 3: nobody runs attendance, leave or payroll on their own record ---------

    @Test
    @DisplayName("HR cannot decide their own leave - the admin does; HR still decides everyone else's")
    void hrCannotDecideOwnLeave() {
        long own = applyLeave("ACHR", "2031-05-13", hrToken);

        assertThat(decide(own, "approve", hrToken).status()).isEqualTo(404);
        assertThat(decide(own, "reject", hrToken).status()).isEqualTo(404);
        assertThat(decide(own, "cancel", hrToken).status()).isEqualTo(404);
        Resp endorsed = decide(own, "supervisor-approve", hrToken);
        assertThat(endorsed.status()).isEqualTo(400);
        assertThat(endorsed.body().get("message").asString()).contains("does not manage");
        assertThat(send("POST", "/api/leaves/hr-create", leave("ACHR", "2031-05-14"), hrToken).status())
                .isEqualTo(404);

        assertThat(decide(own, "approve", adminToken).status()).isEqualTo(200);
        assertThat(decide(applyLeave("ACEMP", "2031-05-15", hrToken), "approve", hrToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("HR cannot unlock or generate their own attendance - the admin does")
    void hrCannotTouchOwnAttendance() {
        assertThat(send("POST", "/api/attendance/ACHR/unlock?month=2031-05", null, hrToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/attendance/generate", generate("ACHR"), hrToken).status()).isEqualTo(404);

        assertThat(send("POST", "/api/attendance/ACEMP/unlock?month=2031-05", null, hrToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/attendance/generate", generate("ACEMP"), hrToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/attendance/ACHR/unlock?month=2031-05", null, adminToken).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("HR cannot run their own payroll - a whole-company run leaves their row for the admin")
    void hrCannotRunOwnPayroll() {
        assertThat(send("POST", "/api/payroll/generate", payroll("ACHR"), hrToken).status()).isEqualTo(403);

        JsonNode run = send("POST", "/api/payroll/generate-all?" + MAY, null, hrToken).body();
        assertThat(userIds(run.get("succeeded"), "employeeId")).containsExactlyInAnyOrder("ACSUP", "ACEMP");
        assertThat(run.get("errors")).isEmpty();

        assertThat(send("POST", "/api/payroll/generate", payroll("ACHR"), adminToken).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("HR cannot give themselves leave days - the admin can")
    void hrCannotSetOwnLeaveQuota() {
        String quota = "?year=2031&leaveType=CASUAL_LEAVE&quota=30";

        assertThat(send("PUT", "/api/leave-balances/ACHR" + quota, null, hrToken).status()).isEqualTo(403);
        assertThat(send("PUT", "/api/leave-balances/ACEMP" + quota, null, hrToken).status()).isEqualTo(200);
        assertThat(send("PUT", "/api/leave-balances/ACHR" + quota, null, adminToken).status()).isEqualTo(200);
    }

    // ---- part 4: a company has one admin -----------------------------------------------

    @Test
    @DisplayName("there is exactly one admin: nobody can add, promote, demote or deactivate one")
    void thereIsOnlyOneAdmin() {
        for (String token : List.of(adminToken, hrToken)) {
            assertThat(send("POST", "/api/employees", withRole(record("ACSECOND", 20000), "ADMIN"), token).status())
                    .isEqualTo(400);
        }
        assertThat(employeeRepository.findByUserId("ACSECOND")).isEmpty();

        assertThat(send("PUT", "/api/employees/" + empId, withRole(record("ACEMP", 31000), "ADMIN"), adminToken)
                .status()).isEqualTo(400);
        assertThat(employeeRepository.findById(empId).orElseThrow().getRole()).isEqualTo(Role.EMPLOYEE);

        assertThat(send("PUT", "/api/employees/" + adminId, withRole(record("ACADMIN", 1000), "EMPLOYEE"), adminToken)
                .status()).isEqualTo(400);
        assertThat(send("DELETE", "/api/employees/" + adminId, null, adminToken).status()).isEqualTo(400);

        Employee admin = employeeRepository.findById(adminId).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
    }

    // ---- helpers -------------------------------------------------------------------

    private String withRole(String json, String role) {
        return json.replace("\"status\"", "\"role\": \"" + role + "\", \"status\"");
    }

    private String generate(String userId) {
        return "{\"month\": \"2031-05\", \"userIds\": [\"" + userId + "\"], \"generatedBy\": \"x\"}";
    }

    private String payroll(String userId) {
        return "{\"employeeId\": \"" + userId + "\", \"month\": 5, \"year\": 2031, \"generatedBy\": \"x\"}";
    }

    private long applyLeave(String userId, String date, String token) {
        Resp applied = send("POST", "/api/leaves", leave(userId, date), token);
        assertThat(applied.status()).as(String.valueOf(applied.body())).isEqualTo(201);
        return applied.body().get("id").asLong();
    }

    private Resp decide(long leaveId, String action, String token) {
        return send("POST", "/api/leaves/" + leaveId + "/" + action, "{\"approverId\": \"x\", \"comments\": \"ok\"}",
                token);
    }

    private String leave(String userId) {
        return leave(userId, "2031-05-12");
    }

    private String leave(String userId, String date) {
        return """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "%s", "toDate": "%s",
                 "duration": "FULL_DAY"}""".formatted(userId, date, date);
    }

    private String record(String userId, int grossSalary) {
        return """
                {"userId": "%s", "employeeName": "%s Name", "status": "PERMANENT",
                 "grossSalary": %d, "pfBasic": 15000, "medicalAllowance": 1000, "otherAllowance": 0}"""
                .formatted(userId, userId, grossSalary);
    }

    private List<String> userIds(JsonNode rows, String field) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get(field).asString()));
        return ids;
    }

    private record Resp(int status, JsonNode body) {
    }

    /** The admin carries no pay - the way onboarding creates them; everyone else is paid 31,000. */
    private Employee saveEmployee(String userId, Role role, Company company, Employee supervisor) {
        boolean paid = role != Role.ADMIN;
        Employee employee = Employee.builder()
                .userId(userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(paid ? LocalDate.of(2022, 1, 1) : null)
                .grossSalary(paid ? new BigDecimal("31000") : null).pfBasic(paid ? new BigDecimal("15000") : null)
                .medicalAllowance(paid ? new BigDecimal("1000") : null).otherAllowance(paid ? BigDecimal.ZERO : null)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        if (paid) {
            salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRuleForCompany(company));
        }
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
        leaveRequestRepository.deleteAll();
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

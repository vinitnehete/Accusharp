package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.CustomRolePermissionRepository;
import com.accusharp.hrms.repository.CustomRoleRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A custom role's permissions work all the way through, not just at the
 * {@code @PreAuthorize} gate.
 *
 * <p>Before this, a custom role granting {@code LEAVE_APPROVE} or
 * {@code ATTENDANCE_CORRECT} got a supervisor past the controller and then
 * straight into a service check that asked for the HR or ADMIN role by name.
 * Now the permission decides <em>what</em> may be done, and the fixed role still
 * decides <em>whose</em> records: HR/ADMIN anyone in the company, a SUPERVISOR
 * their direct reports - never their own record - and a plain EMPLOYEE nobody.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomRoleCapabilityHttpTest {

    private static final String PASSWORD = "Capability-Test-1";
    private static final LocalDate DAY = LocalDate.of(2031, 5, 12);
    private static final String DECISION = "{\"approverId\": \"ignored\", \"comments\": \"ok\"}";
    private static final String GENERATE_MAY = "{\"month\": \"2031-05\", \"generatedBy\": \"ignored\"}";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmployeeCustomRoleRepository employeeCustomRoleRepository;
    @Autowired private CustomRolePermissionRepository customRolePermissionRepository;
    @Autowired private CustomRoleRepository customRoleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String adminToken;
    private String hrToken;
    private String supervisorToken;
    private String plainToken;

    @BeforeEach
    void setUp() {
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        auditLogRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("CAP-CO").companyName("Capability Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("CAPADMIN", Role.ADMIN, company, null);
        saveEmployee("CAPHR", Role.HR, company, null);
        Employee supervisor = saveEmployee("CAPSUP", Role.SUPERVISOR, company, null);
        saveEmployee("CAPTEAM", Role.EMPLOYEE, company, supervisor);
        saveEmployee("CAPOTHER", Role.EMPLOYEE, company, null);
        saveEmployee("CAPPLAIN", Role.EMPLOYEE, company, null);

        adminToken = login("CAPADMIN");
        hrToken = login("CAPHR");
        supervisorToken = login("CAPSUP");
        plainToken = login("CAPPLAIN");
    }

    // ---- leave -------------------------------------------------------------

    @Test
    @DisplayName("a supervisor given LEAVE_APPROVE by a custom role gives final approval for their own team")
    void supervisorWithLeaveApproveApprovesOwnTeam() {
        long teamLeave = pendingLeave("CAPTEAM");

        assertThat(approve(teamLeave, supervisorToken).status()).isEqualTo(403);

        grant("CAPSUP", "LEAVE_APPROVE");

        Resp approved = approve(teamLeave, supervisorToken);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("that supervisor still cannot approve leave outside their team, or their own")
    void supervisorWithLeaveApproveCannotReachBeyondTeam() {
        long othersLeave = pendingLeave("CAPOTHER");
        long ownLeave = pendingLeave("CAPSUP");
        grant("CAPSUP", "LEAVE_APPROVE");

        assertThat(approve(othersLeave, supervisorToken).status()).isEqualTo(404);
        assertThat(approve(ownLeave, supervisorToken).status()).isEqualTo(404);
        assertThat(leaveRequestRepository.findById(othersLeave).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
        assertThat(leaveRequestRepository.findById(ownLeave).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("that supervisor cannot reject or cancel their own leave either, but can their team's")
    void supervisorWithLeaveApproveCannotDecideOwnLeave() {
        long ownLeave = pendingLeave("CAPSUP");
        long teamLeave = pendingLeave("CAPTEAM");
        grant("CAPSUP", "LEAVE_APPROVE");

        assertThat(send("POST", "/api/leaves/" + ownLeave + "/reject", DECISION, supervisorToken).status())
                .isEqualTo(404);
        assertThat(send("POST", "/api/leaves/" + ownLeave + "/cancel", DECISION, supervisorToken).status())
                .isEqualTo(404);
        assertThat(leaveRequestRepository.findById(ownLeave).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);

        assertThat(send("POST", "/api/leaves/" + teamLeave + "/reject", DECISION, supervisorToken).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a supervisor given LEAVE_APPROVE enters an approved leave directly for their team, nobody else")
    void supervisorWithLeaveApproveEntersLeaveForOwnTeamOnly() {
        grant("CAPSUP", "LEAVE_APPROVE");

        assertThat(hrDirect("CAPTEAM", supervisorToken).status()).isEqualTo(201);
        assertThat(hrDirect("CAPOTHER", supervisorToken).status()).isEqualTo(404);
        assertThat(hrDirect("CAPSUP", supervisorToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a plain EMPLOYEE given LEAVE_APPROVE manages nobody, so approves nothing")
    void employeeWithLeaveApproveManagesNobody() {
        long othersLeave = pendingLeave("CAPOTHER");
        long ownLeave = pendingLeave("CAPPLAIN");
        grant("CAPPLAIN", "LEAVE_APPROVE");

        assertThat(approve(othersLeave, plainToken).status()).isEqualTo(404);
        assertThat(approve(ownLeave, plainToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("HR still approves anyone else's leave - their own is for the admin")
    void hrApprovesAnyoneButThemselves() {
        assertThat(approve(pendingLeave("CAPSUP"), hrToken).status()).isEqualTo(200);

        long ownLeave = pendingLeave("CAPHR");
        assertThat(approve(ownLeave, hrToken).status()).isEqualTo(404);
        assertThat(approve(ownLeave, adminToken).status()).isEqualTo(200);
    }

    // ---- attendance --------------------------------------------------------

    @Test
    @DisplayName("a supervisor given ATTENDANCE_CORRECT corrects their team's attendance, not anyone else's or their own")
    void supervisorWithAttendanceCorrectCorrectsOwnTeamOnly() {
        assertThat(send("POST", "/api/attendance/generate", GENERATE_MAY, hrToken).status()).isEqualTo(200);

        assertThat(correct("CAPTEAM", supervisorToken).status()).isEqualTo(403);

        grant("CAPSUP", "ATTENDANCE_CORRECT");

        Resp corrected = correct("CAPTEAM", supervisorToken);
        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.body().get("status").asString()).isEqualTo("PRESENT");
        assertThat(correct("CAPOTHER", supervisorToken).status()).isEqualTo(404);
        assertThat(correct("CAPSUP", supervisorToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a supervisor given ATTENDANCE_GENERATE generates for their team alone")
    void supervisorWithAttendanceGenerateGeneratesOwnTeamOnly() {
        grant("CAPSUP", "ATTENDANCE_GENERATE");

        Resp generated = send("POST", "/api/attendance/generate", GENERATE_MAY, supervisorToken);
        assertThat(generated.status()).isEqualTo(200);
        assertThat(generated.body().get("employeesProcessed").asInt()).isEqualTo(1);
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate("CAPTEAM", DAY)).isPresent();
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate("CAPOTHER", DAY)).isEmpty();
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate("CAPSUP", DAY)).isEmpty();

        Resp namingOutsider = send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-05\", \"generatedBy\": \"ignored\", \"userIds\": [\"CAPOTHER\"]}", supervisorToken);
        assertThat(namingOutsider.status()).isEqualTo(404);
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate("CAPOTHER", DAY)).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private void grant(String userId, String... codes) {
        Resp role = send("POST", "/api/roles", "{\"name\": \"Grant for " + userId + "\"}", adminToken);
        long roleId = role.body().get("id").asLong();
        String json = Arrays.stream(codes).map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(", ", "{\"permissionCodes\": [", "]}"));
        assertThat(send("PUT", "/api/roles/" + roleId + "/permissions", json, adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + roleId + "/employees/" + userId, null, adminToken).status())
                .isEqualTo(204);
    }

    private long pendingLeave(String userId) {
        return leaveRequestRepository.save(LeaveRequest.builder()
                .userId(userId).leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(DAY).toDate(DAY)
                .duration(LeaveDuration.FULL_DAY).totalDays(new BigDecimal("1.0"))
                .status(LeaveStatus.PENDING).origin(LeaveOrigin.SELF_SERVICE).appliedAt(Instant.now())
                .build()).getId();
    }

    private Resp approve(long leaveId, String token) {
        return send("POST", "/api/leaves/" + leaveId + "/approve",
                "{\"approverId\": \"ignored\", \"comments\": \"ok\"}", token);
    }

    private Resp hrDirect(String userId, String token) {
        return send("POST", "/api/leaves/hr-create", """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-05-20", "toDate": "2031-05-20",
                 "duration": "FULL_DAY", "reason": "Backfill"}""".formatted(userId), token);
    }

    private Resp correct(String userId, String token) {
        return send("PUT", "/api/attendance/" + userId + "/" + DAY,
                "{\"status\": \"PRESENT\", \"remarks\": \"Device was down\", \"updatedBy\": \"ignored\"}", token);
    }

    private Employee saveEmployee(String userId, Role role, Company company, Employee supervisor) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
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
                    .uri(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
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
}

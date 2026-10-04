package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.entity.Payroll;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far down the organisation a caller can see, granted as a permission.
 *
 * <p>A SUPERVISOR sees their direct reports, which is no use to a director:
 * their reports are team leads, and the people who actually do the work are a
 * level further down. Rather than a second kind of role, scope is three
 * grantable permissions - {@code SCOPE_DIRECT_REPORTS}, {@code SCOPE_ALL_REPORTS},
 * {@code SCOPE_COMPANY} - seeded onto the fixed roles exactly as they behaved
 * before, and grantable through a custom role for anything wider. Every screen
 * follows: the directory, reports, the dashboard, leave decisions, attendance
 * corrections and the roster.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectorScopeHttpTest {

    private static final String PASSWORD = "Director-Scope-1";
    private static final LocalDate DAY = LocalDate.of(2031, 5, 12);
    private static final String PERIOD = "?month=5&year=2031";

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
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String adminToken;
    private String directorToken;

    @BeforeEach
    void setUp() {
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        payrollRepository.deleteAll();
        auditLogRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("DIR-CO").companyName("Director Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("DSADMIN", Role.ADMIN, company, null);
        // Director -> team lead -> worker, plus people outside that line.
        Employee director = saveEmployee("DSDIR", Role.SUPERVISOR, company, null);
        Employee teamLead = saveEmployee("DSTL", Role.SUPERVISOR, company, director);
        saveEmployee("DSWORKER", Role.EMPLOYEE, company, teamLead);
        saveEmployee("DSOUTSIDE", Role.EMPLOYEE, company, null);
        Employee lead = saveEmployee("DSLEAD", Role.EMPLOYEE, company, null);
        saveEmployee("DSLEDBY", Role.EMPLOYEE, company, lead);

        for (String userId : List.of("DSDIR", "DSTL", "DSWORKER", "DSOUTSIDE")) {
            payrollRepository.save(payroll(userId));
        }

        adminToken = login("DSADMIN");
        directorToken = login("DSDIR");
    }

    @Test
    @DisplayName("a supervisor sees one level down until a scope is granted, and reports alone grant nothing")
    void withoutAWiderScopeOnlyDirectReportsAreVisible() {
        assertThat(userIds(get("/api/employees", directorToken))).containsExactlyInAnyOrder("DSDIR", "DSTL");
        assertThat(send("GET", "/api/employees/by-user-id/DSWORKER", null, directorToken).status()).isEqualTo(404);

        // DSLEAD has a direct report but a plain EMPLOYEE role and no scope permission.
        assertThat(userIds(get("/api/employees", login("DSLEAD")))).containsExactly("DSLEAD");
    }

    @Test
    @DisplayName("SCOPE_ALL_REPORTS opens everyone below a director - directory, reports and dashboard alike")
    void allReportsScopeOpensTheWholeSubtree() {
        // PAY_READ too: scope decides whose records a report holds, never whether it may show pay.
        grant("DSDIR", "SCOPE_ALL_REPORTS", "PAY_READ");

        assertThat(userIds(get("/api/employees", directorToken)))
                .containsExactlyInAnyOrder("DSDIR", "DSTL", "DSWORKER");
        assertThat(send("GET", "/api/employees/by-user-id/DSWORKER", null, directorToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/employees/by-user-id/DSOUTSIDE", null, directorToken).status()).isEqualTo(404);

        assertThat(userIds(get("/api/reports/payroll" + PERIOD, directorToken)))
                .containsExactlyInAnyOrder("DSDIR", "DSTL", "DSWORKER");
        assertThat(get("/api/dashboard", directorToken).get("cards").get("totalEmployees").asLong()).isEqualTo(3);

        // The planner lists who this caller rosters, never themselves - exactly as
        // a supervisor's planner has always behaved, now two levels deep.
        List<String> planner = new ArrayList<>();
        get("/api/shift-schedules/planner?month=2031-05", directorToken).get("rows")
                .forEach(row -> planner.add(row.get("userId").asString()));
        assertThat(planner).containsExactlyInAnyOrder("DSTL", "DSWORKER");
    }

    @Test
    @DisplayName("a director with LEAVE_APPROVE decides a skip-level report's leave, but not their own or an outsider's")
    void directorApprovesSkipLevelLeave() {
        grant("DSDIR", "SCOPE_ALL_REPORTS", "LEAVE_APPROVE");

        assertThat(approve(pendingLeave("DSWORKER"), directorToken).status()).isEqualTo(200);
        assertThat(approve(pendingLeave("DSOUTSIDE"), directorToken).status()).isEqualTo(404);
        assertThat(approve(pendingLeave("DSDIR"), directorToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a director with ATTENDANCE_CORRECT corrects a skip-level report's day")
    void directorCorrectsSkipLevelAttendance() {
        assertThat(send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-05\", \"generatedBy\": \"ignored\"}", adminToken).status()).isEqualTo(200);
        grant("DSDIR", "SCOPE_ALL_REPORTS", "ATTENDANCE_CORRECT");

        assertThat(correct("DSWORKER", directorToken).status()).isEqualTo(200);
        assertThat(correct("DSOUTSIDE", directorToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("SCOPE_COMPANY on a custom role opens the whole company to a plain employee")
    void companyScopeOpensEveryone() {
        String clerk = login("DSLEAD");
        grant("DSLEAD", "SCOPE_COMPANY", "REPORT_READ", "PAY_READ");

        // The admin login is a company account, not staff - never in the directory.
        assertThat(userIds(get("/api/employees", clerk)))
                .contains("DSDIR", "DSTL", "DSWORKER", "DSOUTSIDE", "DSLEAD")
                .doesNotContain("DSADMIN");
        assertThat(userIds(get("/api/reports/payroll" + PERIOD, clerk)))
                .containsExactlyInAnyOrder("DSDIR", "DSTL", "DSWORKER", "DSOUTSIDE");
        // Scope is reach, not power: deciding leave still needs LEAVE_APPROVE.
        assertThat(approve(pendingLeave("DSWORKER"), clerk).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("HR and ADMIN still see the whole company with no custom role at all")
    void fixedRolesKeepTheirScope() {
        assertThat(userIds(get("/api/employees", adminToken)))
                .containsExactlyInAnyOrder("DSDIR", "DSTL", "DSWORKER", "DSOUTSIDE", "DSLEAD", "DSLEDBY");
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private JsonNode get(String path, String token) {
        Resp response = send("GET", path, null, token);
        assertThat(response.status()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private List<String> userIds(JsonNode rows) {
        List<String> userIds = new ArrayList<>();
        rows.forEach(row -> userIds.add(row.get("userId").asString()));
        return userIds;
    }

    private void grant(String userId, String... codes) {
        long roleId = send("POST", "/api/roles", "{\"name\": \"Scope for " + userId + "\"}", adminToken)
                .body().get("id").asLong();
        String json = Arrays.stream(codes).map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(", ", "{\"permissionCodes\": [", "]}"));
        assertThat(send("PUT", "/api/roles/" + roleId + "/permissions", json, adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + roleId + "/employees/" + userId, null, adminToken).status())
                .isEqualTo(204);
    }

    private long pendingLeave(String userId) {
        return leaveRequestRepository.save(LeaveRequest.builder()
                .userId(userId).leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(DAY.plusDays(userId.length())).toDate(DAY.plusDays(userId.length()))
                .duration(LeaveDuration.FULL_DAY).totalDays(new BigDecimal("1.0"))
                .status(LeaveStatus.PENDING).origin(LeaveOrigin.SELF_SERVICE).appliedAt(Instant.now())
                .build()).getId();
    }

    private Resp approve(long leaveId, String token) {
        return send("POST", "/api/leaves/" + leaveId + "/approve",
                "{\"approverId\": \"ignored\", \"comments\": \"ok\"}", token);
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

    private Payroll payroll(String employeeId) {
        BigDecimal zero = BigDecimal.ZERO;
        return Payroll.builder()
                .employeeId(employeeId).employeeName(employeeId + " Name").month(5).year(2031).revision(1)
                .status(PayrollStatus.GENERATED)
                .daysInMonth(31).workingDays(26)
                .presentDays(zero).paidLeaveDays(zero).lopDays(zero).payableDays(zero)
                .earnGrossSalary(new BigDecimal("20000")).totalEarnings(new BigDecimal("20000"))
                .totalDeduction(zero).netSalary(new BigDecimal("20000"))
                .generatedAt(Instant.now())
                .build();
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

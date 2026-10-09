package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.CustomRolePermissionRepository;
import com.accusharp.hrms.repository.CustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
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
 * Reports and the dashboard, as a SUPERVISOR sees them: their own team only.
 *
 * <p>Reports used to be company-wide for anyone holding {@code REPORT_READ},
 * which every SUPERVISOR does - so any supervisor could pull the whole
 * company's payroll register, PF/ESIC/PT figures and bank advice straight
 * from the API even though the UI hid the menu. The team here is the same one
 * self-service scoping already uses (the supervisor plus their direct
 * reports), so a report can never show a supervisor someone they could not
 * open on their own. HR and ADMIN still see the whole company, including
 * employees who have since been deactivated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupervisorReportScopingHttpTest {

    private static final String PASSWORD = "Report-Scope-Test-1";
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
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String adminToken;
    private String hrToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        payrollRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("RPT-SCOPE").companyName("Report Scope Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("RSADM01", Role.ADMIN, company, null, RecordStatus.ACTIVE);
        saveEmployee("RSHR01", Role.HR, company, null, RecordStatus.ACTIVE);
        Employee supervisor = saveEmployee("RSSUP01", Role.SUPERVISOR, company, null, RecordStatus.ACTIVE);
        saveEmployee("RSTEAM01", Role.EMPLOYEE, company, supervisor, RecordStatus.ACTIVE);
        saveEmployee("RSOTHER01", Role.EMPLOYEE, company, null, RecordStatus.ACTIVE);
        saveEmployee("RSLEFT01", Role.EMPLOYEE, company, null, RecordStatus.INACTIVE);

        for (String userId : List.of("RSSUP01", "RSTEAM01", "RSOTHER01", "RSLEFT01")) {
            payrollRepository.save(payroll(userId));
        }

        adminToken = login("RSADM01");
        hrToken = login("RSHR01");
        supervisorToken = login("RSSUP01");
    }

    @AfterEach
    void tearDown() {
        // Custom roles reference employees; leave none behind for the next test class.
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
    }

    @Test
    @DisplayName("the employee report lists a supervisor's own team, not the company")
    void employeeReportIsOwnTeam() {
        assertThat(userIds(get("/api/reports/employees", supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");
        assertThat(userIds(get("/api/reports/employees", hrToken)))
                .contains("RSHR01", "RSSUP01", "RSTEAM01", "RSOTHER01");
    }

    @Test
    @DisplayName("payroll and statutory reports hold only the supervisor's team - rows and totals")
    void payrollReportsAreOwnTeam() {
        grantPayRead("RSSUP01");
        assertThat(userIds(get("/api/reports/payroll" + PERIOD, supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");
        assertThat(userIds(get("/api/reports/statutory/pf" + PERIOD, supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");
        assertThat(userIds(get("/api/reports/payroll/register" + PERIOD, supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");

        long departmentHeadcount = 0;
        for (JsonNode group : get("/api/reports/payroll/by-department" + PERIOD, supervisorToken)) {
            departmentHeadcount += group.get("employeeCount").asLong();
        }
        assertThat(departmentHeadcount).isEqualTo(2);

        assertThat(get("/api/reports/payroll/audit/summary" + PERIOD, supervisorToken).get("headcount").asLong())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("HR's payroll report is still the whole company, deactivated employees included")
    void hrPayrollReportIsStillCompanyWide() {
        assertThat(userIds(get("/api/reports/payroll" + PERIOD, hrToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01", "RSOTHER01", "RSLEFT01");
    }

    @Test
    @DisplayName("attendance and leave balance reports hold only the supervisor's team")
    void attendanceAndLeaveReportsAreOwnTeam() {
        for (String userId : List.of("RSSUP01", "RSTEAM01", "RSOTHER01")) {
            monthlyAttendanceSummaryRepository.save(summaryWithLop(userId));
        }

        assertThat(userIds(get("/api/reports/attendance/lop?month=2031-05", supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");
        assertThat(userIds(get("/api/reports/leave-balances?year=2031", supervisorToken)))
                .containsExactlyInAnyOrder("RSSUP01", "RSTEAM01");
        assertThat(userIds(get("/api/reports/leave-balances?year=2031", hrToken)))
                .contains("RSOTHER01");
    }

    @Test
    @DisplayName("a supervisor's dashboard opens, and counts their team rather than the company")
    void supervisorDashboardIsOwnTeam() {
        Resp supervisorDashboard = send("GET", "/api/dashboard", null, supervisorToken);
        assertThat(supervisorDashboard.status()).isEqualTo(200);
        assertThat(supervisorDashboard.body().get("cards").get("totalEmployees").asLong()).isEqualTo(2);

        Resp hrDashboard = send("GET", "/api/dashboard", null, hrToken);
        assertThat(hrDashboard.status()).isEqualTo(200);
        assertThat(hrDashboard.body().get("cards").get("totalEmployees").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("a supervisor cannot drill into the day-wise audit of someone outside their team")
    void auditDrillDownOutsideTeamIsNotFound() {
        grantPayRead("RSSUP01");
        assertThat(send("GET", "/api/reports/payroll/audit/RSOTHER01/days" + PERIOD, null, supervisorToken).status())
                .isEqualTo(404);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Pay is the employee's own, or for whoever holds PAY_READ - so a supervisor's team-scoped pay
     * reports are only reachable once a custom role grants it. Scope then decides whose rows.
     */
    private void grantPayRead(String userId) {
        long roleId = send("POST", "/api/roles", "{\"name\": \"Pay for " + userId + "\"}", adminToken)
                .body().get("id").asLong();
        assertThat(send("PUT", "/api/roles/" + roleId + "/permissions", "{\"permissionCodes\": [\"PAY_READ\"]}",
                adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + roleId + "/employees/" + userId, null, adminToken).status())
                .isEqualTo(204);
    }

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

    private Employee saveEmployee(String userId, Role role, Company company, Employee supervisor,
                                  RecordStatus recordStatus) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
                .status(EmployeeStatus.PERMANENT).recordStatus(recordStatus).role(role)
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
                .earnPf(new BigDecimal("8000")).pfDeduction(new BigDecimal("960"))
                .totalDeduction(new BigDecimal("960")).netSalary(new BigDecimal("19040"))
                .generatedAt(java.time.Instant.now())
                .build();
    }

    private MonthlyAttendanceSummary summaryWithLop(String userId) {
        BigDecimal zero = BigDecimal.ZERO;
        return MonthlyAttendanceSummary.builder()
                .userId(userId).month("2031-05").workingDays(26)
                .presentDays(new BigDecimal("25")).absentDays(BigDecimal.ONE).halfDays(0).leaveDays(zero)
                .holidayDays(0).weekOffDays(5).lateCount(0).earlyExitCount(0).invalidPunches(0)
                .totalHours(zero).overtimeHours(zero).lopDays(BigDecimal.ONE)
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

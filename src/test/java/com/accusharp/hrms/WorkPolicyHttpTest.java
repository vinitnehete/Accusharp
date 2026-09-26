package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import com.accusharp.hrms.repository.WorkPolicyRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A work policy: which populations follow the attendance process at all, and
 * which are simply paid their salary.
 *
 * <p>Company owners and directors typically punch nothing, roster nothing and
 * ask nobody to approve their leave, but are paid every month and carry the
 * usual statutory deductions. Until now payroll refused to run for them at all
 * - it demands generated attendance for every employee - so the only way
 * through was to fake a month of attendance for someone who was never tracked.
 *
 * <p>Resolution mirrors the attendance policy engine exactly: most specific
 * scope wins outright, versions succeed by effective date, and a company that
 * configures nothing keeps today's behaviour to the rupee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkPolicyHttpTest {

    private static final String PASSWORD = "Work-Policy-1";
    private static final String MAY = "\"month\": 5, \"year\": 2031";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private WorkPolicyRepository workPolicyRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        workPolicyRepository.deleteAll();
        holidayRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        auditLogRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("WP-CO").companyName("Work Policy Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("WPHR", Role.HR, company, LocalDate.of(2022, 1, 1));
        saveEmployee("WPSUP", Role.SUPERVISOR, company, LocalDate.of(2022, 1, 1));
        saveEmployee("WPDIR", Role.EMPLOYEE, company, LocalDate.of(2022, 1, 1));
        saveEmployee("WPJOINER", Role.EMPLOYEE, company, LocalDate.of(2031, 5, 16));
        saveEmployee("WPWORKER", Role.EMPLOYEE, company, LocalDate.of(2022, 1, 1));

        hrToken = login("WPHR");
        supervisorToken = login("WPSUP");
    }

    /**
     * Work policies point at a company, and every other test class clears the
     * company table in its own setup - a policy left behind here would fail that
     * delete on the foreign key. The class that writes the rows clears them.
     */
    @AfterEach
    void tearDown() {
        workPolicyRepository.deleteAll();
    }

    @Test
    @DisplayName("with no policy configured payroll still demands generated attendance, exactly as before")
    void noPolicyKeepsTodaysBehaviour() {
        Resp refused = generatePayroll("WPWORKER");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("Attendance has not been generated");
    }

    @Test
    @DisplayName("a fixed-monthly employee is paid a full month with no attendance at all")
    void fixedMonthlyPaysWithoutAttendance() {
        createPolicy("EMPLOYEE", "WPDIR", "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");

        Resp payroll = generatePayroll("WPDIR");

        assertThat(payroll.status()).isEqualTo(201);
        assertThat(payroll.body().get("payableDays").asDouble()).isEqualTo(31.0);
        assertThat(payroll.body().get("lopDays").asDouble()).isEqualTo(0.0);
        // The whole structure, not a prorated share of it.
        assertThat(payroll.body().get("earnGrossSalary").asDouble())
                .isEqualTo(payroll.body().get("grossSalaryWage").asDouble());
        assertThat(payroll.body().get("netSalary").asDouble()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a fixed-monthly joiner is paid from their joining date, not the whole month")
    void fixedMonthlyProratesAJoiner() {
        createPolicy("EMPLOYEE", "WPJOINER", "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");

        Resp payroll = generatePayroll("WPJOINER");

        assertThat(payroll.status()).isEqualTo(201);
        // Joined on the 16th of a 31-day month.
        assertThat(payroll.body().get("payableDays").asDouble()).isEqualTo(16.0);
        assertThat(payroll.body().get("earnGrossSalary").asDouble())
                .isLessThan(payroll.body().get("grossSalaryWage").asDouble());
    }

    @Test
    @DisplayName("attendance generation leaves untracked employees alone")
    void attendanceGenerationSkipsUntrackedEmployees() {
        createPolicy("EMPLOYEE", "WPDIR", "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");

        Resp generated = send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-05\", \"generatedBy\": \"ignored\"}", hrToken);

        assertThat(generated.status()).isEqualTo(200);
        assertThat(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        "WPDIR", LocalDate.of(2031, 5, 1), LocalDate.of(2031, 5, 31))).isEmpty();
        assertThat(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        "WPWORKER", LocalDate.of(2031, 5, 1), LocalDate.of(2031, 5, 31))).isNotEmpty();
    }

    @Test
    @DisplayName("the most specific policy wins outright, so one person can be tracked inside an untracked company")
    void mostSpecificPolicyWins() {
        createPolicy("COMPANY", null, "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");
        createPolicy("EMPLOYEE", "WPWORKER", "TRACKED", "ATTENDANCE_BASED", "2031-05-01");

        assertThat(generatePayroll("WPDIR").status()).isEqualTo(201);

        Resp worker = generatePayroll("WPWORKER");
        assertThat(worker.status()).isEqualTo(400);
        assertThat(worker.body().get("message").asString()).contains("Attendance has not been generated");
    }

    @Test
    @DisplayName("a payroll records the policy it was paid under, so a later change cannot rewrite it")
    void payrollSnapshotsThePolicy() {
        createPolicy("EMPLOYEE", "WPDIR", "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");

        Resp payroll = generatePayroll("WPDIR");

        assertThat(payroll.body().get("payrollMode").asString()).isEqualTo("FIXED_MONTHLY");
        assertThat(payroll.body().get("workPolicyScope").asString()).isEqualTo("EMPLOYEE");
        assertThat(payroll.body().get("workPolicyVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a policy that pays from attendance it does not track is refused")
    void contradictoryPolicyIsRefused() {
        Resp refused = send("POST", "/api/work-policies", """
                {"scope": "COMPANY", "attendanceTracking": "NOT_TRACKED",
                 "payrollMode": "ATTENDANCE_BASED", "effectiveFrom": "2031-05-01"}""", hrToken);

        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("work policies are HR's to write and a supervisor's to neither read nor write")
    void managingAPolicyNeedsThePermission() {
        assertThat(send("GET", "/api/work-policies", null, hrToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/work-policies", null, supervisorToken).status()).isEqualTo(403);
        assertThat(send("POST", "/api/work-policies", """
                {"scope": "COMPANY", "attendanceTracking": "NOT_TRACKED",
                 "payrollMode": "FIXED_MONTHLY", "effectiveFrom": "2031-05-01"}""", supervisorToken).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the effective policy for one employee is readable, so HR can see which rule applies and why")
    void effectivePolicyIsExplained() {
        createPolicy("COMPANY", null, "NOT_TRACKED", "FIXED_MONTHLY", "2031-05-01");
        createPolicy("EMPLOYEE", "WPWORKER", "TRACKED", "ATTENDANCE_BASED", "2031-05-01");

        Resp effective = send("GET", "/api/work-policies/effective?userId=WPWORKER&date=2031-05-20", null, hrToken);

        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("scope").asString()).isEqualTo("EMPLOYEE");
        assertThat(effective.body().get("attendanceTracking").asString()).isEqualTo("TRACKED");
        assertThat(effective.body().get("payrollMode").asString()).isEqualTo("ATTENDANCE_BASED");
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private void createPolicy(String scope, String scopeRef, String tracking, String payrollMode, String from) {
        String body = """
                {"scope": "%s", %s"attendanceTracking": "%s", "payrollMode": "%s", "effectiveFrom": "%s"}"""
                .formatted(scope, scopeRef == null ? "" : "\"scopeRef\": \"" + scopeRef + "\", ",
                        tracking, payrollMode, from);
        Resp created = send("POST", "/api/work-policies", body, hrToken);
        assertThat(created.status()).as("create work policy: " + created.body()).isEqualTo(201);
    }

    private Resp generatePayroll(String userId) {
        return send("POST", "/api/payroll/generate",
                "{\"employeeId\": \"" + userId + "\", " + MAY + ", \"generatedBy\": \"ignored\"}", hrToken);
    }

    private Employee saveEmployee(String userId, Role role, Company company, LocalDate joiningDate) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(joiningDate)
                .grossSalary(new BigDecimal("31000")).pfBasic(new BigDecimal("15000"))
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

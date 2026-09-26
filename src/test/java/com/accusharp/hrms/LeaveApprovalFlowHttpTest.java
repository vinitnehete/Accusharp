package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a leave gets approved, per population.
 *
 * <p>Employee applies, supervisor endorses, HR approves is the right flow for
 * most of a company and the wrong one for the rest of it: a director has nobody
 * to endorse theirs, and an owner is not asking their own HR for a day off.
 * {@code WorkPolicy.leaveApproval} says which flow a population follows, and a
 * company that configures nothing keeps the two-step one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveApprovalFlowHttpTest {

    private static final String PASSWORD = "Leave-Flow-1";
    private static final LocalDate FROM = LocalDate.of(2031, 6, 10);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private WorkPolicyRepository workPolicyRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;
    private String supervisorToken;
    private String workerToken;
    private String directorToken;

    @BeforeEach
    void setUp() {
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        workPolicyRepository.deleteAll();
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
                .companyCode("LF-CO").companyName("Leave Flow Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("LFHR", Role.HR, company, null);
        Employee supervisor = saveEmployee("LFSUP", Role.SUPERVISOR, company, null);
        saveEmployee("LFWORKER", Role.EMPLOYEE, company, supervisor);
        saveEmployee("LFDIR", Role.EMPLOYEE, company, null);

        hrToken = login("LFHR");
        supervisorToken = login("LFSUP");
        workerToken = login("LFWORKER");
        directorToken = login("LFDIR");
    }

    @AfterEach
    void tearDown() {
        workPolicyRepository.deleteAll();
    }

    @Test
    @DisplayName("with no policy the two-step flow is unchanged: apply, endorse, approve")
    void defaultFlowIsUnchanged() {
        Resp applied = apply("LFWORKER", workerToken, 1);
        assertThat(applied.status()).isEqualTo(201);
        assertThat(applied.body().get("status").asString()).isEqualTo("PENDING");
        long id = applied.body().get("id").asLong();

        Resp endorsed = send("POST", "/api/leaves/" + id + "/supervisor-approve", DECISION, supervisorToken);
        assertThat(endorsed.status()).isEqualTo(200);
        assertThat(endorsed.body().get("status").asString()).isEqualTo("SUPERVISOR_APPROVED");

        Resp approved = send("POST", "/api/leaves/" + id + "/approve", DECISION, hrToken);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an auto-approved population's leave is approved as it is applied for, and spends the balance once")
    void autoApproveApprovesOnApply() {
        policy("LFDIR", "AUTO_APPROVE");

        Resp applied = apply("LFDIR", directorToken, 1);

        assertThat(applied.status()).isEqualTo(201);
        assertThat(applied.body().get("status").asString()).isEqualTo("APPROVED");
        assertThat(applied.body().get("decidedAt").isNull()).isFalse();
        assertThat(balanceUsed("LFDIR")).isEqualByComparingTo("1.0");

        // Nothing left to decide.
        long id = applied.body().get("id").asLong();
        assertThat(send("POST", "/api/leaves/" + id + "/approve", DECISION, hrToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("auto-approval is not a way around the balance check")
    void autoApproveStillChecksBalance() {
        policy("LFDIR", "AUTO_APPROVE");

        // Casual leave opens at 12 days.
        Resp tooMany = apply("LFDIR", directorToken, 20);

        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.body().get("message").asString()).contains("Insufficient");
        assertThat(leaveRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an HR-only population skips endorsement: there is nobody to endorse, and HR decides")
    void hrOnlySkipsEndorsement() {
        policy("LFDIR", "HR_ONLY");

        Resp applied = apply("LFDIR", directorToken, 1);
        assertThat(applied.body().get("status").asString()).isEqualTo("PENDING");
        long id = applied.body().get("id").asLong();

        Resp endorsed = send("POST", "/api/leaves/" + id + "/supervisor-approve", DECISION, hrToken);
        assertThat(endorsed.status()).isEqualTo(400);
        assertThat(endorsed.body().get("message").asString()).contains("HR");

        assertThat(send("POST", "/api/leaves/" + id + "/approve", DECISION, hrToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the effective policy reports the flow, so HR can see who approves whose leave")
    void effectivePolicyReportsTheFlow() {
        policy("LFDIR", "AUTO_APPROVE");

        Resp effective = send("GET", "/api/work-policies/effective?userId=LFDIR&date=2031-06-10", null, hrToken);

        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("leaveApproval").asString()).isEqualTo("AUTO_APPROVE");

        Resp worker = send("GET", "/api/work-policies/effective?userId=LFWORKER&date=2031-06-10", null, hrToken);
        assertThat(worker.body().get("leaveApproval").asString()).isEqualTo("SUPERVISOR_THEN_HR");
    }

    // ---- helpers -----------------------------------------------------------

    private static final String DECISION = "{\"approverId\": \"ignored\", \"comments\": \"ok\"}";

    private record Resp(int status, JsonNode body) {
    }

    private void policy(String userId, String leaveApproval) {
        Resp created = send("POST", "/api/work-policies", """
                {"scope": "EMPLOYEE", "scopeRef": "%s", "attendanceTracking": "NOT_TRACKED",
                 "payrollMode": "FIXED_MONTHLY", "leaveApproval": "%s", "effectiveFrom": "2031-01-01"}"""
                .formatted(userId, leaveApproval), hrToken);
        assertThat(created.status()).as("create work policy: " + created.body()).isEqualTo(201);
    }

    private Resp apply(String userId, String token, int days) {
        return send("POST", "/api/leaves", """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "%s", "toDate": "%s",
                 "duration": "FULL_DAY", "reason": "Personal"}"""
                .formatted(userId, FROM, FROM.plusDays(days - 1L)), token);
    }

    private BigDecimal balanceUsed(String userId) {
        return leaveBalanceRepository.findAll().stream()
                .filter(balance -> balance.getUserId().equals(userId)
                        && balance.getLeaveType() == LeaveType.CASUAL_LEAVE)
                .findFirst().orElseThrow().getUsed();
    }

    private Employee saveEmployee(String userId, Role role, Company company, Employee supervisor) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
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

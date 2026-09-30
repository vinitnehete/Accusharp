package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.CategoryRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leave rules for the populations a company actually has, and leave that
 * accrues month by month.
 *
 * <p>Three gaps this closes. A rule could only name the whole company or one of
 * the four legacy employment statuses, so "directors get no casual leave" had
 * nowhere to live; earned leave accrued for ever with no ceiling, since the only
 * cap was the one applied at year end; and casual and sick leave could only be
 * granted up front for the whole year, which is not how a company that credits
 * one CL a month runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveRuleTargetingAndAccrualHttpTest {

    private static final String PASSWORD = "Leave-Rules-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private WorkPolicyRepository workPolicyRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;

    @BeforeEach
    void setUp() {
        leaveCreditRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        payrollRepository.deleteAll();
        workPolicyRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        auditLogRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        categoryRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("LR-CO").companyName("Leave Rules Co").status(RecordStatus.ACTIVE).build());
        Category directors = categoryRepository.save(Category.builder()
                .categoryCode("DIRECTOR").categoryName("Director").company(company).build());

        saveEmployee("LRHR", Role.HR, company, null);
        saveEmployee("LRDIR", Role.EMPLOYEE, company, directors);
        saveEmployee("LRWORKER", Role.EMPLOYEE, company, null);

        hrToken = login("LRHR");
    }

    /** Rules, policies and a category all point at the company every other test class clears in its own setup. */
    @AfterEach
    void tearDown() {
        leaveCreditRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        workPolicyRepository.deleteAll();
        employeeRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("a leave rule can name one category, and beats the company-wide rule for those employees")
    void ruleCanTargetOneCategory() {
        createRule("""
                {"scope": "COMPANY", "leaveType": "CASUAL_LEAVE", "grantMethod": "YEARLY_GRANT",
                 "yearlyDays": 12, "effectiveFrom": "2031-01-01"}""");
        createRule("""
                {"scope": "CATEGORY", "scopeRef": "DIRECTOR", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2031-01-01"}""");

        assertThat(quota("LRWORKER", "CASUAL_LEAVE")).isEqualByComparingTo("12.0");
        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("a rule naming one employee beats the category rule above it")
    void ruleCanTargetOneEmployee() {
        createRule("""
                {"scope": "CATEGORY", "scopeRef": "DIRECTOR", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2031-01-01"}""");
        createRule("""
                {"scope": "EMPLOYEE", "scopeRef": "LRDIR", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 5, "effectiveFrom": "2031-01-01"}""");

        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("one leave rule is saved for several employees at once, one rule each")
    void oneRuleForSeveralEmployees() {
        Resp created = send("POST", "/api/leave-rules/batch", """
                {"scope": "EMPLOYEE", "scopeRefs": ["LRDIR", "LRWORKER"], "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 5, "effectiveFrom": "2031-01-01"}""", hrToken);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).hasSize(2);
        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("5.0");
        assertThat(quota("LRWORKER", "CASUAL_LEAVE")).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("if one employee in the list does not exist, no leave rule is saved")
    void aWrongEmployeeSavesNoLeaveRule() {
        Resp refused = send("POST", "/api/leave-rules/batch", """
                {"scope": "EMPLOYEE", "scopeRefs": ["LRDIR", "NOBODY"], "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 5, "effectiveFrom": "2031-01-01"}""", hrToken);

        assertThat(refused.status()).isEqualTo(404);
        assertThat(leaveRuleRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a rule naming a population nobody is in is refused, rather than silently never applying")
    void unknownScopeRefIsRefused() {
        Resp refused = send("POST", "/api/leave-rules", """
                {"scope": "CATEGORY", "scopeRef": "NOBODY", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2031-01-01"}""", hrToken);

        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("casual leave can accrue a day a month instead of a year's worth up front")
    void monthlyAccrualCreditsEachMonth() {
        untrackedAndPaidMonthly("LRDIR");
        createRule("""
                {"scope": "EMPLOYEE", "scopeRef": "LRDIR", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "MONTHLY_ACCRUAL", "monthlyCredit": 1, "effectiveFrom": "2031-01-01"}""");

        // Nothing up front - it is earned month by month.
        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("0.0");

        runPayroll("LRDIR", 5);
        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("1.0");

        runPayroll("LRDIR", 6);
        assertThat(quota("LRDIR", "CASUAL_LEAVE")).isEqualByComparingTo("2.0");
    }

    @Test
    @DisplayName("a yearly accrual cap stops the balance growing past it, month after month")
    void accrualStopsAtTheYearlyCap() {
        untrackedAndPaidMonthly("LRDIR");
        createRule("""
                {"scope": "EMPLOYEE", "scopeRef": "LRDIR", "leaveType": "EARNED_LEAVE",
                 "grantMethod": "EARNED_BY_ATTENDANCE", "yearlyAccrualCap": 3, "effectiveFrom": "2031-01-01"}""");

        // May is 31 days on the books, and the statutory floor of one day per 20
        // worked (1.55, rounded up) beats the rule's 1.5 full-month credit.
        runPayroll("LRDIR", 5);
        assertThat(quota("LRDIR", "EARNED_LEAVE")).isEqualByComparingTo("1.6");

        // June would earn 1.5 and the cap allows only 1.4 of it.
        runPayroll("LRDIR", 6);
        assertThat(quota("LRDIR", "EARNED_LEAVE")).isEqualByComparingTo("3.0");

        // The third month is entirely past the cap, so it credits nothing.
        runPayroll("LRDIR", 7);
        assertThat(quota("LRDIR", "EARNED_LEAVE")).isEqualByComparingTo("3.0");
    }

    @Test
    @DisplayName("with no cap the accrual keeps going, exactly as before")
    void withoutACapAccrualIsUnchanged() {
        untrackedAndPaidMonthly("LRWORKER");
        createRule("""
                {"scope": "EMPLOYEE", "scopeRef": "LRWORKER", "leaveType": "EARNED_LEAVE",
                 "grantMethod": "EARNED_BY_ATTENDANCE", "effectiveFrom": "2031-01-01"}""");

        runPayroll("LRWORKER", 5);
        runPayroll("LRWORKER", 6);
        runPayroll("LRWORKER", 7);

        // 1.6 for each 31-day month and 1.5 for June, by the statutory floor.
        assertThat(quota("LRWORKER", "EARNED_LEAVE")).isEqualByComparingTo("4.7");
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private void createRule(String json) {
        Resp created = send("POST", "/api/leave-rules", json, hrToken);
        assertThat(created.status()).as("create leave rule: " + created.body()).isEqualTo(201);
    }

    /** Paid a fixed salary, so payroll - which is what posts a month's accrual - runs with no attendance. */
    private void untrackedAndPaidMonthly(String userId) {
        Resp created = send("POST", "/api/work-policies", """
                {"scope": "EMPLOYEE", "scopeRef": "%s", "attendanceTracking": "NOT_TRACKED",
                 "payrollMode": "FIXED_MONTHLY", "effectiveFrom": "2031-01-01"}""".formatted(userId), hrToken);
        assertThat(created.status()).isEqualTo(201);
    }

    private void runPayroll(String userId, int month) {
        Resp payroll = send("POST", "/api/payroll/generate",
                "{\"employeeId\": \"" + userId + "\", \"month\": " + month
                        + ", \"year\": 2031, \"generatedBy\": \"ignored\"}", hrToken);
        assertThat(payroll.status()).as("payroll " + month + ": " + payroll.body()).isEqualTo(201);
    }

    private BigDecimal quota(String userId, String leaveType) {
        Resp balances = send("GET", "/api/leave-balances/" + userId + "?year=2031", null, hrToken);
        assertThat(balances.status()).isEqualTo(200);
        for (JsonNode balance : balances.body()) {
            if (balance.get("leaveType").asString().equals(leaveType)) {
                return new BigDecimal(balance.get("quota").asString());
            }
        }
        throw new IllegalStateException("no " + leaveType + " balance for " + userId + ": " + balances.body());
    }

    private Employee saveEmployee(String userId, Role role, Company company, Category category) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company).category(category)
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

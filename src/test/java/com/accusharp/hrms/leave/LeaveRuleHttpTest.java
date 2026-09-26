package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
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
 * The leave-rule, year-close and credit-history endpoints over real HTTP, as
 * the roles that will call them.
 *
 * <p>Rules are HR configuration - they decide how much paid leave a whole
 * population gets - so reading them needs {@code LEAVE_BALANCE_MANAGE}, the same
 * permission that overrides a balance today. An employee reads their own credit
 * history, which is what answers "why did I get 1.2 this month?".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveRuleHttpTest {

    private static final String PASSWORD = "Leave-Rule-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;
    private String empToken;
    private String otherCompanyHrToken;

    @BeforeEach
    void setUp() {
        clean();
        Company companyA = companyRepository.save(Company.builder()
                .companyCode("RULE-A").companyName("Rule A").status(RecordStatus.ACTIVE).build());
        Company companyB = companyRepository.save(Company.builder()
                .companyCode("RULE-B").companyName("Rule B").status(RecordStatus.ACTIVE).build());

        saveEmployee("HRA01", Role.HR, companyA);
        saveEmployee("EMPA01", Role.EMPLOYEE, companyA);
        saveEmployee("HRB01", Role.HR, companyB);

        hrToken = login("HRA01");
        empToken = login("EMPA01");
        otherCompanyHrToken = login("HRB01");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- managing rules ----------------------------------------------------

    @Test
    @DisplayName("HR creates a rule and sees it listed")
    void hrCreatesAndListsARule() {
        Resp created = send("POST", "/api/leave-rules", """
                {"scope": "EMPLOYMENT_TYPE", "scopeRef": "DAY_WISE", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2026-01-01"}""", hrToken);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("id")).isNotNull();

        Resp listed = send("GET", "/api/leave-rules", null, hrToken);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body().toString()).contains("DAY_WISE").contains("NOT_ENTITLED");
    }

    @Test
    @DisplayName("a rule's effective date is the first of its month - rules apply to whole months")
    void effectiveFromIsTheFirstOfTheMonth() {
        Resp created = send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "EARNED_LEAVE",
                 "grantMethod": "EARNED_BY_ATTENDANCE", "carryForwardCap": 30,
                 "effectiveFrom": "2026-09-17"}""", hrToken);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("effectiveFrom").asString()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("an employee can neither see nor change rules")
    void employeeCannotManageRules() {
        assertThat(send("GET", "/api/leave-rules", null, empToken).status()).isEqualTo(403);
        assertThat(send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 30, "effectiveFrom": "2026-01-01"}""",
                empToken).status()).isEqualTo(403);
    }

    // ---- what a rule may say -----------------------------------------------

    @Test
    @DisplayName("only earned leave can be earned from attendance")
    void earnedByAttendanceIsOnlyForEarnedLeave() {
        Resp refused = send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "EARNED_BY_ATTENDANCE", "effectiveFrom": "2026-01-01"}""", hrToken);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().toString()).contains("EARNED_LEAVE");
    }

    @Test
    @DisplayName("a yearly grant has to say how many days")
    void yearlyGrantNeedsItsDays() {
        assertThat(send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "effectiveFrom": "2026-01-01"}""", hrToken).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("unpaid leave has no balance, so it takes no rules")
    void unpaidLeaveTakesNoRules() {
        assertThat(send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "LEAVE_WITHOUT_PAY",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2026-01-01"}""", hrToken).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("an employment-type rule has to name a real employment type")
    void employmentTypeMustExist() {
        assertThat(send("POST", "/api/leave-rules", """
                {"scope": "EMPLOYMENT_TYPE", "scopeRef": "FREELANCER", "leaveType": "CASUAL_LEAVE",
                 "grantMethod": "NOT_ENTITLED", "effectiveFrom": "2026-01-01"}""", hrToken).status())
                .isEqualTo(400);
    }

    // ---- tenant isolation --------------------------------------------------

    @Test
    @DisplayName("another company's HR can neither see nor change the rule")
    void anotherCompanyCannotTouchTheRule() {
        Resp created = send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 7, "effectiveFrom": "2026-01-01"}""", hrToken);
        long id = created.body().get("id").asLong();

        Resp update = send("PUT", "/api/leave-rules/" + id, """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 99, "effectiveFrom": "2026-01-01"}""",
                otherCompanyHrToken);

        assertThat(update.status()).isEqualTo(404);
        assertThat(send("GET", "/api/leave-rules", null, otherCompanyHrToken).body().toString())
                .doesNotContain("\"yearlyDays\":7");
    }

    @Test
    @DisplayName("HR can move a rule to another month; moving it onto an existing rule is a conflict")
    void editingTheEffectiveMonth() {
        long id = send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 7, "effectiveFrom": "2026-01-01"}""", hrToken)
                .body().get("id").asLong();

        // The rule must not collide with itself when its own key changes.
        Resp moved = send("PUT", "/api/leave-rules/" + id, """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 7, "effectiveFrom": "2026-04-01"}""", hrToken);
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("effectiveFrom").asString()).isEqualTo("2026-04-01");

        send("POST", "/api/leave-rules", """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 9, "effectiveFrom": "2026-07-01"}""", hrToken);

        Resp collided = send("PUT", "/api/leave-rules/" + id, """
                {"scope": "COMPANY", "scopeRef": "ANY", "leaveType": "SICK_LEAVE",
                 "grantMethod": "YEARLY_GRANT", "yearlyDays": 7, "effectiveFrom": "2026-07-01"}""", hrToken);
        assertThat(collided.status()).isEqualTo(409);
    }

    // ---- year close and credit history -------------------------------------

    @Test
    @DisplayName("HR closes the year; an employee cannot")
    void closeYearIsHrOnly() {
        Resp closed = send("POST", "/api/leave-balances/close-year?year=2026", null, hrToken);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.body().isArray()).isTrue();

        assertThat(send("POST", "/api/leave-balances/close-year?year=2026", null, empToken).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("an employee reads their own credit history")
    void employeeReadsTheirOwnCredits() {
        Resp credits = send("GET", "/api/leave-balances/EMPA01/credits?year=2026", null, empToken);

        assertThat(credits.status()).isEqualTo(200);
        assertThat(credits.body().isArray()).isTrue();
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

    private void saveEmployee(String userId, Role role, Company company) {
        employeeRepository.save(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}

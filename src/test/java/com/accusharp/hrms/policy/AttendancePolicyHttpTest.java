package com.accusharp.hrms.policy;

import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.*;
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
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The policy API's security and validation surface.
 *
 * <p>Mirrors {@link com.accusharp.hrms.AttendanceRuleHttpTest}'s shape, because
 * the tenancy question is the same one and the answer must be too: a company
 * sees its own rules plus the shared catalog, writes only its own, and a
 * cross-company id 404s rather than 403s - never confirming another company's
 * row exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendancePolicyHttpTest {

    private static final String PASSWORD = "Policy-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private AttendancePolicyRuleRepository policyRuleRepository;
    @Autowired private AttendancePolicyApplicationRepository policyApplicationRepository;
    @Autowired private AttendancePolicyOutcomeRepository policyOutcomeRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company companyA;
    private String hrTokenA;
    private String hrTokenB;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        cleanAll();

        companyA = companyRepository.save(Company.builder()
                .companyCode("POL-A").companyName("Policy A").status(RecordStatus.ACTIVE).build());
        Company companyB = companyRepository.save(Company.builder()
                .companyCode("POL-B").companyName("Policy B").status(RecordStatus.ACTIVE).build());

        shiftRepository.save(Shift.builder().company(companyA)
                .shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(0).overtimeWindowMinutes(240).build());

        Category staffA = categoryRepository.save(Category.builder()
                .company(companyA).categoryCode("STAFF").categoryName("Staff").build());

        saveEmployee("HRA001", companyA, Role.HR, null);
        saveEmployee("HRB001", companyB, Role.HR, null);
        saveEmployee("EMP-A01", companyA, Role.EMPLOYEE, staffA);

        hrTokenA = login("HRA001");
        hrTokenB = login("HRB001");
        employeeToken = login("EMP-A01");
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    // ---- authorisation -----------------------------------------------------

    @Test
    @DisplayName("a plain EMPLOYEE token can neither read nor write attendance policy")
    void employeeTokenIsRefused() {
        assertThat(send("GET", "/api/attendance-policy/rules", null, employeeToken).status())
                .isEqualTo(403);
        assertThat(send("POST", "/api/attendance-policy/rules", lateArrival("STAFF", 15, "2026-09-01"),
                employeeToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("an unauthenticated caller is refused outright")
    void anonymousIsRefused() {
        assertThat(send("GET", "/api/attendance-policy/rules", null, null).status())
                .isIn(401, 403);
    }

    // ---- tenancy -----------------------------------------------------------

    @Test
    @DisplayName("a company never sees another company's rules")
    void rulesAreScopedPerCompany() {
        assertThat(send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2026-09-01"), hrTokenA).status()).isEqualTo(200);

        Resp seenByA = send("GET", "/api/attendance-policy/rules", null, hrTokenA);
        assertThat(seenByA.body()).hasSize(1);

        Resp seenByB = send("GET", "/api/attendance-policy/rules", null, hrTokenB);
        assertThat(seenByB.body()).isEmpty();
    }

    @Test
    @DisplayName("deleting another company's rule 404s - the same shape an unknown id returns, so it never confirms the row exists")
    void crossCompanyDeleteIs404() {
        Resp created = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2099-01-01"), hrTokenA);
        long id = created.body().get("id").asLong();

        assertThat(send("DELETE", "/api/attendance-policy/rules/" + id, null, hrTokenB).status())
                .isEqualTo(404);
        assertThat(send("DELETE", "/api/attendance-policy/rules/999999", null, hrTokenB).status())
                .isEqualTo(404);

        // Still there for its owner.
        assertThat(send("GET", "/api/attendance-policy/rules", null, hrTokenA).body()).hasSize(1);
    }

    // ---- append-only versioning -------------------------------------------

    @Test
    @DisplayName("saving the same rule again appends v2 rather than editing v1 - the chain is the history")
    void savingAgainAppendsAVersion() {
        assertThat(send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2026-09-01"), hrTokenA).body().get("version").asInt())
                .isEqualTo(1);

        Resp second = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 30, "2026-10-01"), hrTokenA);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("version").asInt()).isEqualTo(2);

        assertThat(send("GET", "/api/attendance-policy/rules", null, hrTokenA).body()).hasSize(2);
    }

    @Test
    @DisplayName("two versions of one rule starting on the same date are refused by the database constraint, not just a service check")
    void duplicateEffectiveDateIsRejected() {
        assertThat(send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2026-09-01"), hrTokenA).status()).isEqualTo(200);

        Resp duplicate = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 20, "2026-09-01"), hrTokenA);
        assertThat(duplicate.status()).isIn(409, 400);
    }

    @Test
    @DisplayName("a version that has not started yet can be deleted; one already in force must be superseded instead")
    void onlyFutureVersionsAreDeletable() {
        Resp future = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2099-01-01"), hrTokenA);
        assertThat(send("DELETE", "/api/attendance-policy/rules/" + future.body().get("id").asLong(),
                null, hrTokenA).status()).isEqualTo(200);

        Resp past = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2020-01-01"), hrTokenA);
        Resp refused = send("DELETE", "/api/attendance-policy/rules/" + past.body().get("id").asLong(),
                null, hrTokenA);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .contains("may have priced days already")
                .contains("enabled=false");
    }

    // ---- validation --------------------------------------------------------

    @Test
    @DisplayName("params that do not match the rule type are refused by name, not accepted and misread later")
    void unknownParamFieldIsRejected() {
        Resp rejected = send("POST", "/api/attendance-policy/rules", """
                {"scope":"CATEGORY","scopeRef":"STAFF","ruleType":"LATE_ARRIVAL",
                 "effectiveFrom":"2026-09-01","enabled":true,
                 "params":{"graceMins":15,"penaltyStatus":"HALF_DAY"}}""", hrTokenA);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString())
                .contains("params do not match LATE_ARRIVAL");
    }

    @Test
    @DisplayName("a parameter outside its bounds is refused at the boundary, on write")
    void outOfBoundsParamIsRejected() {
        Resp rejected = send("POST", "/api/attendance-policy/rules", """
                {"scope":"CATEGORY","scopeRef":"STAFF","ruleType":"LATE_MARK_ACCUMULATION",
                 "effectiveFrom":"2026-09-01","enabled":true,
                 "params":{"minimumLateMinutes":1,"occurrencesPerPenalty":0,"penaltyLopDays":0.5}}""",
                hrTokenA);

        // occurrencesPerPenalty is a divisor - zero would be a runtime failure
        // during payroll rather than a 400 here.
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString()).contains("occurrencesPerPenalty");
    }

    @Test
    @DisplayName("MISSING_PUNCH cannot award a full day - a single punch is no evidence anybody stayed")
    void missingPunchCannotAwardPresent() {
        Resp rejected = send("POST", "/api/attendance-policy/rules", """
                {"scope":"COMPANY","ruleType":"MISSING_PUNCH",
                 "effectiveFrom":"2026-09-01","enabled":true,
                 "params":{"fallbackStatus":"PRESENT","onTimeGraceMinutes":15}}""", hrTokenA);

        assertThat(rejected.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a scope reference matching no employee is refused - otherwise the rule silently never applies")
    void scopeRefThatNamesNothingIsRejected() {
        Resp rejected = send("POST", "/api/attendance-policy/rules",
                lateArrival("NO-SUCH-CATEGORY", 15, "2026-09-01"), hrTokenA);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString())
                .contains("no employee in this company has CATEGORY 'NO-SUCH-CATEGORY'");
    }

    // ---- the back-dating guard --------------------------------------------

    @Test
    @DisplayName("a rule back-dated into a locked, paid month is refused - a report would otherwise silently re-price it")
    void backdatingIntoALockedMonthIsRefused() {
        dailyAttendanceRepository.save(DailyAttendance.builder()
                .userId("EMP-A01").attendanceDate(LocalDate.of(2026, 8, 10))
                .shiftCode("GENERAL")
                .workingHours(new BigDecimal("8.00")).breakHours(new BigDecimal("1.00"))
                .overtimeHours(BigDecimal.ZERO).lateMinutes(0).earlyExitMinutes(0)
                .invalidPunch(false).weekOff(false).holiday(false)
                .status(AttendanceStatus.PRESENT).recordStatus(AttendanceRecordStatus.GENERATED)
                .locked(true)
                .build());

        Resp refused = send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2026-08-01"), hrTokenA);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .contains("falls inside a locked period")
                .contains("2026-08")
                .contains("already been paid");

        // Dated after the locked month, the same rule is accepted.
        assertThat(send("POST", "/api/attendance-policy/rules",
                lateArrival("STAFF", 15, "2026-09-01"), hrTokenA).status()).isEqualTo(200);
    }

    // ---- explainability ----------------------------------------------------

    @Test
    @DisplayName("the effective endpoint names the rule that won, why, and the rules it beat")
    void effectivePolicyShowsWinnerAndBeaten() {
        send("POST", "/api/attendance-policy/rules", """
                {"scope":"COMPANY","ruleType":"LATE_ARRIVAL","effectiveFrom":"2026-09-01",
                 "enabled":true,"params":{"graceMinutes":5,"penaltyStatus":"HALF_DAY"}}""", hrTokenA);
        send("POST", "/api/attendance-policy/rules", lateArrival("STAFF", 15, "2026-09-01"), hrTokenA);

        Resp effective = send("GET",
                "/api/attendance-policy/effective?userId=EMP-A01&date=2026-09-15", null, hrTokenA);
        assertThat(effective.status()).isEqualTo(200);

        JsonNode lateArrival = null;
        for (JsonNode rule : effective.body().get("rules")) {
            if ("LATE_ARRIVAL".equals(rule.get("ruleType").asString())) {
                lateArrival = rule;
            }
        }
        assertThat(lateArrival).isNotNull();
        assertThat(lateArrival.get("applied").get("label").asString())
                .isEqualTo("LATE_ARRIVAL v1 scoped CATEGORY=STAFF");
        assertThat(lateArrival.get("appliedBecause").asString()).contains("CATEGORY=STAFF");
        // The company rule matched and lost, and says so.
        assertThat(lateArrival.get("beaten")).hasSize(1);
        assertThat(lateArrival.get("beaten").get(0).get("label").asString()).contains("COMPANY");

        // The AttendanceRule thresholds the policy sits on are returned too, so
        // "why is this day a half day" has one place to look.
        assertThat(effective.body().get("base").get("fullDayThresholdPercent").asDouble()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("a rule type nobody configured says so explicitly rather than returning an ambiguous blank")
    void unconfiguredRuleTypeIsExplained() {
        Resp effective = send("GET",
                "/api/attendance-policy/effective?userId=EMP-A01&date=2026-09-15", null, hrTokenA);

        for (JsonNode rule : effective.body().get("rules")) {
            assertThat(rule.get("applied").isNull()).isTrue();
            assertThat(rule.get("appliedBecause").asString())
                    .contains("no rule of this type is configured");
        }
    }

    @Test
    @DisplayName("reading another company's employee 404s, the same as an unknown userId")
    void effectivePolicyIsTenantChecked() {
        assertThat(send("GET", "/api/attendance-policy/effective?userId=EMP-A01&date=2026-09-15",
                null, hrTokenB).status()).isEqualTo(404);
    }

    // ---- helpers -----------------------------------------------------------

    private String lateArrival(String categoryCode, int graceMinutes, String effectiveFrom) {
        return """
                {"scope":"CATEGORY","scopeRef":"%s","ruleType":"LATE_ARRIVAL",
                 "effectiveFrom":"%s","enabled":true,
                 "params":{"graceMinutes":%d,"penaltyStatus":"HALF_DAY"}}"""
                .formatted(categoryCode, effectiveFrom, graceMinutes);
    }

    private void cleanAll() {
        payrollRepository.deleteAll();
        policyApplicationRepository.deleteAll();
        policyOutcomeRepository.deleteAll();
        policyRuleRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        categoryRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        holidayRepository.deleteAll();
        companyRepository.deleteAll();
    }

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
                    .uri(URI.create("http://localhost:"
                            + environment.getProperty("local.server.port") + path))
                    .header("Content-Type", "application/json")
                    .method(method, payload);
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? null : objectMapper.readTree(response.body());
            return new Resp(response.statusCode(), body);
        } catch (Exception ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
    }

    private Employee saveEmployee(String userId, Company company, Role role, Category category) {
        return employeeRepository.save(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company).category(category)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }
}

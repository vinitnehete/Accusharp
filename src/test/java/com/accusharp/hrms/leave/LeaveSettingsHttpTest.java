package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
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
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A company's leave year: January (calendar) or April (financial), set by that
 * company's HR and nobody else's.
 *
 * <p>It can be switched only while the company has no leave taken, pending or
 * credited. Leave already taken came out of a balance numbered by the old year,
 * and after a switch cancelling it would give the days back to a different
 * balance. A balance merely <em>created</em> - which happens automatically the
 * first time anyone opens the balances page - blocks nothing: it has no history
 * to misplace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveSettingsHttpTest {

    private static final String PASSWORD = "Leave-Settings-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;
    private String empToken;
    private String otherCompanyHrToken;

    @BeforeEach
    void setUp() {
        clean();
        Company companyA = companyRepository.save(Company.builder()
                .companyCode("SET-A").companyName("Settings A").status(RecordStatus.ACTIVE).build());
        Company companyB = companyRepository.save(Company.builder()
                .companyCode("SET-B").companyName("Settings B").status(RecordStatus.ACTIVE).build());

        saveEmployee("HRA02", Role.HR, companyA);
        saveEmployee("EMPA02", Role.EMPLOYEE, companyA);
        saveEmployee("HRB02", Role.HR, companyB);

        hrToken = login("HRA02");
        empToken = login("EMPA02");
        otherCompanyHrToken = login("HRB02");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("a company starts on the calendar year, and can change it")
    void calendarYearByDefault() {
        Resp settings = send("GET", "/api/leave-settings", null, hrToken);

        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.body().get("leaveYearStartMonth").asInt()).isEqualTo(1);
        assertThat(settings.body().get("canChange").asBoolean()).isTrue();
        assertThat(settings.body().get("currentLeaveYearLabel").asString())
                .isEqualTo(String.valueOf(LocalDate.now().getYear()));
    }

    @Test
    @DisplayName("HR switches their company to the financial year; another company is untouched")
    void hrSwitchesToTheFinancialYear() {
        Resp switched = send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 4}", hrToken);

        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.body().get("leaveYearStartMonth").asInt()).isEqualTo(4);
        assertThat(switched.body().get("currentLeaveYearLabel").asString()).matches("\\d{4}-\\d{2}");

        assertThat(send("GET", "/api/leave-settings", null, hrToken).body().get("leaveYearStartMonth").asInt())
                .isEqualTo(4);
        assertThat(send("GET", "/api/leave-settings", null, otherCompanyHrToken).body()
                .get("leaveYearStartMonth").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an employee can read the leave year but not change it")
    void employeeReadsButCannotChange() {
        assertThat(send("GET", "/api/leave-settings", null, empToken).status()).isEqualTo(200);
        assertThat(send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 4}", empToken).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the leave year starts in January or April, nothing else")
    void onlyJanuaryOrApril() {
        assertThat(send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 7}", hrToken).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("once leave has been taken, the leave year cannot be switched")
    void leaveTakenBlocksTheSwitch() {
        balance("EMPA02", "1");

        assertThat(send("GET", "/api/leave-settings", null, hrToken).body().get("canChange").asBoolean()).isFalse();

        Resp refused = send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 4}", hrToken);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().toString()).contains("already");
    }

    @Test
    @DisplayName("a pending leave request blocks the switch too")
    void pendingLeaveBlocksTheSwitch() {
        leaveRequestRepository.save(LeaveRequest.builder()
                .userId("EMPA02").leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(LocalDate.of(2026, 11, 2)).toDate(LocalDate.of(2026, 11, 2))
                .duration(LeaveDuration.FULL_DAY).totalDays(BigDecimal.ONE)
                .status(LeaveStatus.PENDING).origin(LeaveOrigin.SELF_SERVICE).appliedAt(Instant.now())
                .build());

        assertThat(send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 4}", hrToken).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a balance nobody has used - opened automatically by viewing it - does not block the switch")
    void anUnusedBalanceDoesNotBlock() {
        balance("EMPA02", "0");

        assertThat(send("PUT", "/api/leave-settings", "{\"leaveYearStartMonth\": 4}", hrToken).status())
                .isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private void balance(String userId, String used) {
        leaveBalanceRepository.save(LeaveBalance.builder()
                .userId(userId).leaveYear(2026).leaveType(LeaveType.CASUAL_LEAVE)
                .quota(new BigDecimal("12")).used(new BigDecimal(used)).build());
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
        leaveRequestRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}

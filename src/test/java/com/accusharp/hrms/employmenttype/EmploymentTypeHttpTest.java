package com.accusharp.hrms.employmenttype;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.EmploymentTypeRepository;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The employment-type API's security and validation surface - the same tenancy
 * questions {@code Shift} and {@code Category} answer, asked of the table that
 * decides how somebody is paid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmploymentTypeHttpTest {

    private static final String PASSWORD = "Emp-Type-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmploymentTypeRepository employmentTypeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrTokenA;
    private String hrTokenB;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        clean();
        Company companyA = companyRepository.save(Company.builder()
                .companyCode("ET-A").companyName("Type Co A").status(RecordStatus.ACTIVE).build());
        Company companyB = companyRepository.save(Company.builder()
                .companyCode("ET-B").companyName("Type Co B").status(RecordStatus.ACTIVE).build());

        saveEmployee("HRA900", companyA, Role.HR);
        saveEmployee("HRB900", companyB, Role.HR);
        saveEmployee("EMP900", companyA, Role.EMPLOYEE);

        hrTokenA = login("HRA900");
        hrTokenB = login("HRB900");
        employeeToken = login("EMP900");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("seeding defaults creates the four types that reproduce today's behaviour, and is idempotent")
    void seedDefaultsIsIdempotent() {
        Resp first = send("POST", "/api/employment-types/seed-defaults", null, hrTokenA);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).hasSize(4);

        // Running it again creates nothing - a company that has already
        // customised DAY_WISE must not have it reset.
        Resp second = send("POST", "/api/employment-types/seed-defaults", null, hrTokenA);
        assertThat(second.body()).isEmpty();

        Resp all = send("GET", "/api/employment-types", null, hrTokenA);
        assertThat(all.body()).hasSize(4);
    }

    @Test
    @DisplayName("the seeded DAY_WISE type carries exactly the behaviour the enum hardcodes today")
    void seededDayWiseMatchesTheEnum() {
        send("POST", "/api/employment-types/seed-defaults", null, hrTokenA);

        JsonNode dayWise = null;
        for (JsonNode type : send("GET", "/api/employment-types", null, hrTokenA).body()) {
            if ("DAY_WISE".equals(type.get("typeCode").asString())) {
                dayWise = type;
            }
        }
        assertThat(dayWise).isNotNull();
        assertThat(dayWise.get("payBasis").asString()).isEqualTo("PER_ATTENDED_DAY");
        assertThat(dayWise.get("lopApplies").asBoolean()).isFalse();
        assertThat(dayWise.get("paidLeaveAddsPayableDays").asBoolean()).isFalse();
        assertThat(dayWise.get("overtimeBasis").asString()).isEqualTo("MONTHLY_TOTAL_HOURS");
        assertThat(dayWise.get("paidLeaveEarnsOvertime").asBoolean()).isTrue();
        // Null, not 26 - it inherits the company's salaryRule.dayWiseDaysInMonth
        // rather than freezing a copy that would stop tracking it.
        assertThat(dayWise.get("payableDaysCap").isNull()).isTrue();
    }

    @Test
    @DisplayName("a company can define its own type with its own payable-days cap")
    void aCompanyCanDefineItsOwnType() {
        Resp created = send("POST", "/api/employment-types", """
                {"typeCode":"DAY_WISE_24","typeName":"Day wise, 24-day base",
                 "payBasis":"PER_ATTENDED_DAY","payableDaysCap":24,
                 "lopApplies":false,"paidLeaveAddsPayableDays":false,
                 "overtimeBasis":"MONTHLY_TOTAL_HOURS","paidLeaveEarnsOvertime":true,
                 "segmentedRevisionEarnings":false,"autoRosterDefaultShift":false,"active":true}""",
                hrTokenA);

        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("payableDaysCap").asInt()).isEqualTo(24);
    }

    @Test
    @DisplayName("a per-attended-day type that also applies LOP is refused - it would deduct the same absence twice")
    void perAttendedDayWithLopIsRefused() {
        Resp refused = send("POST", "/api/employment-types", """
                {"typeCode":"BROKEN","typeName":"Broken",
                 "payBasis":"PER_ATTENDED_DAY","lopApplies":true,
                 "overtimeBasis":"MONTHLY_TOTAL_HOURS"}""", hrTokenA);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .contains("cannot also apply loss of pay")
                .contains("deduct the same absence twice");
    }

    @Test
    @DisplayName("a payable-days cap on a calendar-day type is refused - it prorates against the month's own length")
    void capOnCalendarDayTypeIsRefused() {
        Resp refused = send("POST", "/api/employment-types", """
                {"typeCode":"BROKEN2","typeName":"Broken two",
                 "payBasis":"PER_CALENDAR_DAY_LESS_LOP","payableDaysCap":26,
                 "lopApplies":true,"overtimeBasis":"PER_DAY_SHIFT_EXCESS"}""", hrTokenA);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("payableDaysCap applies only");
    }

    @Test
    @DisplayName("a company never sees or edits another company's types")
    void typesAreScopedPerCompany() {
        Resp created = send("POST", "/api/employment-types", """
                {"typeCode":"PRIVATE_A","typeName":"Private A",
                 "payBasis":"PER_CALENDAR_DAY_LESS_LOP","lopApplies":true,
                 "overtimeBasis":"PER_DAY_SHIFT_EXCESS"}""", hrTokenA);
        long id = created.body().get("id").asLong();

        assertThat(send("GET", "/api/employment-types", null, hrTokenB).body()).isEmpty();
        assertThat(send("GET", "/api/employment-types/" + id, null, hrTokenB).status()).isEqualTo(404);
        assertThat(send("DELETE", "/api/employment-types/" + id, null, hrTokenB).status()).isEqualTo(404);

        // Still there for its owner.
        assertThat(send("GET", "/api/employment-types/" + id, null, hrTokenA).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a plain EMPLOYEE token can neither read nor write employment types - this is pay-computation config, not a master-data label")
    void employeeIsRefusedEntirely() {
        // Deliberately unlike CATEGORY_READ, which an EMPLOYEE does hold. A
        // category is a label; an employment type is the rule deciding whether
        // somebody is paid per attended day or per calendar day, which belongs
        // with SALARY_RULE_READ.
        assertThat(send("GET", "/api/employment-types", null, employeeToken).status()).isEqualTo(403);
        assertThat(send("POST", "/api/employment-types", """
                {"typeCode":"NOPE","typeName":"Nope","payBasis":"PER_CALENDAR_DAY_LESS_LOP",
                 "lopApplies":true,"overtimeBasis":"PER_DAY_SHIFT_EXCESS"}""",
                employeeToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a duplicate type code is refused")
    void duplicateCodeIsRefused() {
        String body = """
                {"typeCode":"DUP","typeName":"Dup","payBasis":"PER_CALENDAR_DAY_LESS_LOP",
                 "lopApplies":true,"overtimeBasis":"PER_DAY_SHIFT_EXCESS"}""";
        assertThat(send("POST", "/api/employment-types", body, hrTokenA).status()).isEqualTo(200);
        Resp duplicate = send("POST", "/api/employment-types", body, hrTokenA);
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.body().get("message").asString()).contains("already exists");
    }

    // ---- helpers -----------------------------------------------------------

    private void clean() {
        employeeRepository.deleteAll();
        employmentTypeRepository.deleteAll();
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

    private Resp send(String method, String path, String json, String token) {
        try {
            HttpRequest.BodyPublisher payload = json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:"
                            + environment.getProperty("local.server.port") + path))
                    .header("Content-Type", "application/json")
                    .method(method, payload);
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? null : objectMapper.readTree(response.body());
            return new Resp(response.statusCode(), body);
        } catch (Exception ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
    }

    private Employee saveEmployee(String userId, Company company, Role role) {
        return employeeRepository.save(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }
}

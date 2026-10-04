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
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
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
 * "Nobody acts on their own record" must hold when the id is typed in another
 * case.
 *
 * <p>The production database is MySQL, whose default collation compares user ids
 * without regard to case: {@code cvhr} finds the row {@code CVHR}. The guards that
 * keep HR off their own leave, quota and attendance compared the caller's stored id
 * with the typed one using a case-sensitive {@code equals}, so {@code cvhr} looked
 * like somebody else while every read and write resolved to HR's own row. The normal
 * test database (H2) compares case-sensitively, which is why nothing caught it.
 *
 * <p>This class runs on its own in-memory database with {@code IGNORECASE=TRUE},
 * which behaves like MySQL here, so the same requests that were refused with the
 * exact id are checked with a lower-case one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:hrms-ignorecase;DB_CLOSE_DELAY=-1;MODE=MySQL;IGNORECASE=TRUE")
class CaseVariantUserIdHttpTest {

    private static final String PASSWORD = "Case-Variant-1";
    private static final String DAY = "2031-05-05";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        clean();
        Company company = companyRepository.save(Company.builder()
                .companyCode("CV-CO").companyName("Case Variant Co").status(RecordStatus.ACTIVE).build());
        saveEmployee("CVADMIN", Role.ADMIN, company, null);
        saveEmployee("CVHR", Role.HR, company, null);
        Employee supervisor = saveEmployee("CVSUP", Role.SUPERVISOR, company, null);
        saveEmployee("CVEMP", Role.EMPLOYEE, company, supervisor);

        adminToken = login("CVADMIN");
        hrToken = login("CVHR");
        employeeToken = login("CVEMP");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("the database really does match ids without regard to case here (the premise of every test below)")
    void theDatabaseIgnoresCase() {
        assertThat(employeeRepository.findByUserId("cvhr")).isPresent();
    }

    // ---- leave -------------------------------------------------------------

    @Test
    @DisplayName("a leave applied for under a case variant is stored under the person's real id")
    void leaveIsStoredUnderTheRealId() {
        Resp applied = applyLeave("cvemp", employeeToken);

        assertThat(applied.status()).isEqualTo(201);
        assertThat(applied.body().get("userId").asString()).isEqualTo("CVEMP");
    }

    @Test
    @DisplayName("HR cannot approve, reject or cancel their own leave by typing their id in another case")
    void hrCannotDecideOwnLeaveUnderACaseVariant() {
        long leaveId = applyLeave("cvhr", hrToken).body().get("id").asLong();

        assertThat(decide(leaveId, "approve", hrToken).status()).isEqualTo(404);
        assertThat(decide(leaveId, "reject", hrToken).status()).isEqualTo(404);
        assertThat(decide(leaveId, "cancel", hrToken).status()).isEqualTo(404);
        // ...and the admin, whose job it is, still can.
        assertThat(decide(leaveId, "approve", adminToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("HR cannot endorse their own leave as a \"supervisor\" under a case variant")
    void hrCannotEndorseOwnLeaveUnderACaseVariant() {
        long leaveId = applyLeave("cvhr", hrToken).body().get("id").asLong();

        assertThat(decide(leaveId, "supervisor-approve", hrToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a leave HR applied for in lower case before the fix is still refused to HR on approval")
    void aLegacyRowStoredUnderTheTypedIdIsStillGuarded() {
        // Rows written before the id was normalised keep whatever case was typed.
        long leaveId = leaveRequestRepository.save(com.accusharp.hrms.entity.LeaveRequest.builder()
                .userId("cvhr").leaveType(com.accusharp.hrms.enums.LeaveType.CASUAL_LEAVE)
                .fromDate(LocalDate.parse("2031-05-12")).toDate(LocalDate.parse("2031-05-12"))
                .duration(com.accusharp.hrms.enums.LeaveDuration.FULL_DAY).totalDays(new BigDecimal("1.0"))
                .status(com.accusharp.hrms.enums.LeaveStatus.PENDING)
                .origin(com.accusharp.hrms.enums.LeaveOrigin.SELF_SERVICE)
                .appliedAt(java.time.Instant.now()).build()).getId();

        assertThat(decide(leaveId, "approve", hrToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("HR cannot enter an already-approved leave for themselves under a case variant")
    void hrCannotEnterOwnLeaveDirectlyUnderACaseVariant() {
        assertThat(hrDirect("CVHR", hrToken).status()).as("exact id, the rule as written").isEqualTo(404);
        assertThat(hrDirect("cvhr", hrToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("HR still decides and enters leave for other people, however their id is typed")
    void hrStillHandlesOtherPeopleUnderACaseVariant() {
        long leaveId = applyLeave("cvemp", employeeToken).body().get("id").asLong();

        assertThat(decide(leaveId, "approve", hrToken).status()).isEqualTo(200);
        assertThat(hrDirect("cvemp", hrToken).status()).isEqualTo(201);
    }

    // ---- leave quota -------------------------------------------------------

    @Test
    @DisplayName("HR cannot raise their own leave quota under a case variant, but can for others")
    void hrCannotRaiseOwnQuotaUnderACaseVariant() {
        assertThat(setQuota("CVHR", hrToken).status()).as("exact id, the rule as written").isEqualTo(403);
        assertThat(setQuota("cvhr", hrToken).status()).isEqualTo(403);
        assertThat(setQuota("cvemp", hrToken).status()).isEqualTo(200);
    }

    // ---- attendance --------------------------------------------------------

    @Test
    @DisplayName("HR cannot generate, correct or unlock their own attendance under a case variant")
    void hrCannotTouchOwnAttendanceUnderACaseVariant() {
        assertThat(generate("[\"CVEMP\"]", hrToken).status()).isEqualTo(200);

        assertThat(generate("[\"CVHR\"]", hrToken).status()).as("exact id, the rule as written").isEqualTo(404);
        assertThat(generate("[\"cvhr\"]", hrToken).status()).isEqualTo(404);
        assertThat(correct("cvhr", hrToken).status()).isEqualTo(404);
        assertThat(unlock("cvhr", hrToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("HR still generates, corrects and unlocks other people's attendance under a case variant")
    void hrStillHandlesOtherPeoplesAttendanceUnderACaseVariant() {
        assertThat(generate("[\"cvemp\"]", hrToken).status()).isEqualTo(200);
        assertThat(correct("cvemp", hrToken).status()).isEqualTo(200);
        assertThat(unlock("cvemp", hrToken).status()).isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private Resp applyLeave(String typedUserId, String token) {
        return send("POST", "/api/leaves", """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-05-12", "toDate": "2031-05-12",
                 "duration": "FULL_DAY"}""".formatted(typedUserId), token);
    }

    private Resp decide(long leaveId, String action, String token) {
        return send("POST", "/api/leaves/" + leaveId + "/" + action,
                "{\"approverId\": \"ignored\", \"comments\": \"ok\"}", token);
    }

    private Resp hrDirect(String userId, String token) {
        return send("POST", "/api/leaves/hr-create", """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-05-20", "toDate": "2031-05-20",
                 "duration": "FULL_DAY", "reason": "Backfill"}""".formatted(userId), token);
    }

    private Resp setQuota(String userId, String token) {
        return send("PUT", "/api/leave-balances/" + userId + "?year=2031&leaveType=CASUAL_LEAVE&quota=99", null, token);
    }

    private Resp generate(String userIdsJson, String token) {
        return send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-05\", \"generatedBy\": \"ignored\", \"userIds\": " + userIdsJson + "}", token);
    }

    private Resp correct(String userId, String token) {
        return send("PUT", "/api/attendance/" + userId + "/" + DAY,
                "{\"status\": \"PRESENT\", \"remarks\": \"Device was down\", \"updatedBy\": \"ignored\"}", token);
    }

    private Resp unlock(String userId, String token) {
        return send("POST", "/api/attendance/" + userId + "/unlock?month=2031-05", null, token);
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

    private void clean() {
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        auditLogRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();
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

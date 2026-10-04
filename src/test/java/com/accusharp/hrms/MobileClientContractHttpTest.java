package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the mobile app needs to tell the truth, where the API used to leave it
 * guessing. Each answer is one small additive field or opt-in parameter - the
 * web app ignores them and every existing response keeps its shape.
 *
 * <ul>
 *   <li>a month's attendance says whether the employee is tracked at all, so a
 *       fixed-salary director is not shown 27 red "absent" days;</li>
 *   <li>one's own roster can include the usual shift the attendance engine
 *       assumes, so a supervisor is not told "no shift today" every morning;</li>
 *   <li>a leave request says which approval flow decides it, so the app can say
 *       who it is waiting for and a supervisor is not offered a button the server
 *       refuses.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobileClientContractHttpTest {

    private static final String PASSWORD = "Mobile-Contract-1";
    private static final String MAY = "2031-05";
    private static final LocalDate A_SUNDAY = LocalDate.of(2031, 5, 4);
    private static final LocalDate A_MONDAY = LocalDate.of(2031, 5, 5);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private WorkPolicyRepository workPolicyRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company company;
    private Shift night;
    private String hrToken;
    private String workerToken;
    private String directorToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("MC-CO").companyName("Mobile Contract Co").status(RecordStatus.ACTIVE).build());
        shiftRepository.save(shift("GENERAL", LocalTime.of(9, 0), LocalTime.of(18, 0)));
        night = shiftRepository.save(shift("NIGHT", LocalTime.of(18, 0), LocalTime.of(8, 0)));

        saveEmployee("MCHR", Role.HR, null);
        Employee supervisor = saveEmployee("MCSUP", Role.SUPERVISOR, null);
        saveEmployee("MCWORKER", Role.EMPLOYEE, supervisor);
        saveEmployee("MCDIR", Role.EMPLOYEE, supervisor);

        hrToken = login("MCHR");
        supervisorToken = login("MCSUP");
        workerToken = login("MCWORKER");
        directorToken = login("MCDIR");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- attendance: tracked or not ---------------------------------------

    @Test
    @DisplayName("a month's attendance says the employee is tracked unless a work policy says otherwise")
    void monthlyAttendanceSaysWhetherTheEmployeeIsTracked() {
        assertThat(monthly("MCWORKER", workerToken).get("attendanceTracked").asBoolean()).isTrue();
        assertThat(monthly("MCDIR", directorToken).get("attendanceTracked").asBoolean()).isTrue();

        createPolicy("MCDIR", "NOT_TRACKED", "FIXED_MONTHLY", "HR_ONLY");

        assertThat(monthly("MCDIR", directorToken).get("attendanceTracked").asBoolean()).isFalse();
        assertThat(monthly("MCWORKER", workerToken).get("attendanceTracked").asBoolean())
                .as("a policy for one person leaves everybody else tracked").isTrue();
    }

    @Test
    @DisplayName("the supervisor reading a team member sees the same flag")
    void supervisorSeesTheFlagForTheirTeam() {
        createPolicy("MCDIR", "NOT_TRACKED", "FIXED_MONTHLY", "HR_ONLY");

        assertThat(monthly("MCDIR", supervisorToken).get("attendanceTracked").asBoolean()).isFalse();
    }

    // ---- own roster: the usual shift ---------------------------------------

    @Test
    @DisplayName("without includeUsual the roster is unchanged: only what somebody planned")
    void rosterIsUnchangedByDefault() {
        Resp roster = send("GET", "/api/shift-schedules/MCWORKER?fromDate=" + A_SUNDAY + "&toDate=" + A_MONDAY,
                null, workerToken);

        assertThat(roster.status()).isEqualTo(200);
        assertThat(roster.body().size()).isZero();
    }

    @Test
    @DisplayName("includeUsual adds the usual shift and weekly off the attendance engine assumes, flagged")
    void rosterCanIncludeTheUsualShift() {
        Resp roster = send("GET", "/api/shift-schedules/MCWORKER?fromDate=" + A_SUNDAY + "&toDate=" + A_MONDAY
                + "&includeUsual=true", null, workerToken);

        assertThat(roster.status()).isEqualTo(200);
        assertThat(roster.body().size()).isEqualTo(2);
        JsonNode sunday = roster.body().get(0);
        assertThat(sunday.get("shiftDate").asString()).isEqualTo(A_SUNDAY.toString());
        assertThat(sunday.get("weekOff").asBoolean()).isTrue();
        assertThat(sunday.get("defaulted").asBoolean()).isTrue();
        JsonNode monday = roster.body().get(1);
        assertThat(monday.get("shiftCode").asString()).isEqualTo("GENERAL");
        assertThat(monday.get("startTime").asString()).startsWith("09:00");
        assertThat(monday.get("weekOff").asBoolean()).isFalse();
        assertThat(monday.get("defaulted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a planned day always wins over the usual one, and says it was planned")
    void aPlannedDayWins() {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("MCWORKER").shiftDate(A_MONDAY).shift(night).weekOff(false).assignedBy("MCSUP").build());

        Resp roster = send("GET", "/api/shift-schedules/MCWORKER?fromDate=" + A_MONDAY + "&toDate=" + A_MONDAY
                + "&includeUsual=true", null, workerToken);

        assertThat(roster.body().size()).isEqualTo(1);
        assertThat(roster.body().get(0).get("shiftCode").asString()).isEqualTo("NIGHT");
        assertThat(roster.body().get(0).get("defaulted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a supervisor gets their own usual shift - the team planner never listed them")
    void supervisorGetsTheirOwnUsualShift() {
        Resp roster = send("GET", "/api/shift-schedules/MCSUP?fromDate=" + A_MONDAY + "&toDate=" + A_MONDAY
                + "&includeUsual=true", null, supervisorToken);

        assertThat(roster.status()).isEqualTo(200);
        assertThat(roster.body().size()).isEqualTo(1);
        assertThat(roster.body().get(0).get("shiftCode").asString()).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("includeUsual is no way round the self-service scope")
    void includeUsualDoesNotWidenAccess() {
        Resp roster = send("GET", "/api/shift-schedules/MCSUP?fromDate=" + A_MONDAY + "&toDate=" + A_MONDAY
                + "&includeUsual=true", null, workerToken);

        assertThat(roster.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a person not on an automatic roster gets nothing invented, includeUsual or not")
    void notAutoRosteredGetsNothingInvented() {
        Employee contract = employeeRepository.findByUserId("MCWORKER").orElseThrow();
        contract.setStatus(EmployeeStatus.CONTRACT);
        employeeRepository.saveAndFlush(contract);

        Resp roster = send("GET", "/api/shift-schedules/MCWORKER?fromDate=" + A_MONDAY + "&toDate=" + A_MONDAY
                + "&includeUsual=true", null, workerToken);

        assertThat(roster.body().size()).isZero();
    }

    // ---- leave: who decides ------------------------------------------------

    @Test
    @DisplayName("a leave request carries the flow that decides it, in every read")
    void leaveCarriesTheApprovalFlow() {
        createPolicy("MCDIR", "NOT_TRACKED", "FIXED_MONTHLY", "HR_ONLY");

        Resp worker = applyLeave("MCWORKER", workerToken, "2031-05-12");
        Resp director = applyLeave("MCDIR", directorToken, "2031-05-13");

        assertThat(worker.body().get("approvalFlow").asString()).isEqualTo("SUPERVISOR_THEN_HR");
        assertThat(director.body().get("approvalFlow").asString()).isEqualTo("HR_ONLY");

        Resp history = send("GET", "/api/leaves/employee/MCDIR", null, directorToken);
        assertThat(history.body().get(0).get("approvalFlow").asString()).isEqualTo("HR_ONLY");

        Resp pending = send("GET", "/api/leaves/pending/MCSUP", null, supervisorToken);
        assertThat(pending.body().size()).isEqualTo(2);
        for (JsonNode leave : pending.body()) {
            String expected = "MCDIR".equals(leave.get("userId").asString()) ? "HR_ONLY" : "SUPERVISOR_THEN_HR";
            assertThat(leave.get("approvalFlow").asString()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("an automatically approved leave says so")
    void autoApprovedLeaveSaysSo() {
        createPolicy("MCDIR", "TRACKED", "ATTENDANCE_BASED", "AUTO_APPROVE");

        Resp director = applyLeave("MCDIR", directorToken, "2031-05-13");

        assertThat(director.body().get("status").asString()).isEqualTo("APPROVED");
        assertThat(director.body().get("approvalFlow").asString()).isEqualTo("AUTO_APPROVE");
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private JsonNode monthly(String userId, String token) {
        Resp response = send("GET", "/api/attendance/" + userId + "/monthly?month=" + MAY, null, token);
        assertThat(response.status()).as("monthly attendance of " + userId + ": " + response.body()).isEqualTo(200);
        return response.body();
    }

    private Resp applyLeave(String userId, String token, String date) {
        Resp response = send("POST", "/api/leaves", """
                {"userId": "%s", "leaveType": "CASUAL_LEAVE", "fromDate": "%s", "toDate": "%s",
                 "duration": "FULL_DAY"}""".formatted(userId, date, date), token);
        assertThat(response.status()).as("apply leave: " + response.body()).isEqualTo(201);
        return response;
    }

    private void createPolicy(String userId, String tracking, String payrollMode, String leaveApproval) {
        Resp created = send("POST", "/api/work-policies", """
                {"scope": "EMPLOYEE", "scopeRef": "%s", "attendanceTracking": "%s", "payrollMode": "%s",
                 "leaveApproval": "%s", "effectiveFrom": "2031-01-01"}"""
                .formatted(userId, tracking, payrollMode, leaveApproval), hrToken);
        assertThat(created.status()).as("create work policy: " + created.body()).isEqualTo(201);
    }

    private Shift shift(String code, LocalTime start, LocalTime end) {
        return Shift.builder().company(company).shiftCode(code).shiftName(code)
                .startTime(start).endTime(end)
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build();
    }

    private Employee saveEmployee(String userId, Role role, Employee supervisor) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId + " Name")
                .company(company).supervisor(supervisor)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .weekOffDays(Set.of(DayOfWeek.SUNDAY))
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

    private void clean() {
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        workPolicyRepository.deleteAll();
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

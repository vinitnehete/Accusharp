package com.accusharp.hrms;

import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the attendance endpoints over real HTTP with the exact JSON bodies the
 * Postman collection sends.
 *
 * <p>The service-level tests bypass the web layer entirely, so they cannot
 * catch a binding failure - a {@code YearMonth} in a request body and a
 * {@code LocalDate} in a path variable are both easy to get wrong, and both
 * fail only at the HTTP boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendanceApiHttpTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);
    private static final String EMPLOYEE = "EMP300";
    private static final String HR = "HR300";
    private static final String PASSWORD = "Test-Password-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private String hrToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HR-300", "HR Head", Role.HR);
        saveEmployee(EMPLOYEE, "EMP-300", "Api Worker", Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 20; day++) {
            roster.add(ShiftSchedule.builder().userId(EMPLOYEE).shiftDate(PERIOD.atDay(day))
                    .shift(morning).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);

        List<DeviceLog> punches = new ArrayList<>();
        long id = 20_000;
        for (int day = 1; day <= 19; day++) {
            punches.add(punch(id++, PERIOD.atDay(day).atTime(6, 0)));
            punches.add(punch(id++, PERIOD.atDay(day).atTime(15, 0)));
        }
        // Day 20: entry captured, exit missed - the case that motivated all this.
        punches.add(punch(id, PERIOD.atDay(20).atTime(6, 0)));
        deviceLogRepository.saveAll(punches);

        hrToken = login(HR);
        employeeToken = login(EMPLOYEE);
    }

    @Test
    @DisplayName("the collection's generate and correct payloads bind over HTTP")
    void theDocumentedPayloadsBindCorrectly() {
        // "yyyy-MM" in a JSON body has to reach a YearMonth field.
        Resp generated = send("POST", "/api/attendance/generate", """
                {
                  "month": "2026-10",
                  "userIds": ["EMP300"],
                  "generatedBy": "HR300",
                  "overwriteManual": false,
                  "includeUnrostered": false
                }""", hrToken);

        assertThat(generated.status()).isEqualTo(200);
        assertThat(generated.body().get("daysGenerated").asInt()).isEqualTo(20);
        assertThat(generated.body().get("month").asString()).isEqualTo("2026-10");

        Resp records = send("GET", "/api/attendance/EMP300/records?month=2026-10", null, hrToken);
        assertThat(records.status()).isEqualTo(200);
        assertThat(records.body().size()).isEqualTo(20);
        assertThat(records.body().get(19).get("status").asString()).isEqualTo("INVALID_PUNCH");
        assertThat(records.body().get(19).get("recordStatus").asString()).isEqualTo("GENERATED");

        // ISO date in the path, ISO date-times in the body.
        Resp corrected = send("PUT", "/api/attendance/EMP300/2026-10-20", """
                {
                  "firstIn": "2026-10-20T06:00:00",
                  "lastOut": "2026-10-20T15:00:00",
                  "remarks": "Device missed the exit punch",
                  "updatedBy": "HR300"
                }""", hrToken);

        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.body().get("recordStatus").asString()).isEqualTo("MANUAL");
        assertThat(corrected.body().get("status").asString()).isEqualTo("PRESENT");
        assertThat(corrected.body().get("workingHours").asDouble()).isEqualTo(8.0);

        // Forced status: no punch times at all to supply.
        Resp declared = send("PUT", "/api/attendance/EMP300/2026-10-19", """
                {
                  "status": "HALF_DAY",
                  "remarks": "Left at midday",
                  "updatedBy": "HR300"
                }""", hrToken);
        assertThat(declared.status()).isEqualTo(200);
        assertThat(declared.body().get("status").asString()).isEqualTo("HALF_DAY");
        assertThat(declared.body().get("workingHours").asDouble()).isEqualTo(4.0);
        assertThat(declared.body().get("firstIn").isNull()).isTrue();
    }

    @Test
    @DisplayName("payroll over HTTP refuses an ungenerated period, then locks and unlocks")
    void payrollGuardAndUnlockOverHttp() {
        Resp refused = send("POST", "/api/payroll/generate", """
                {"employeeId": "EMP300", "month": 10, "year": 2026, "generatedBy": "HR300"}""", hrToken);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .contains("Attendance has not been generated");

        send("POST", "/api/attendance/generate", """
                {"month": "2026-10", "userIds": ["EMP300"], "generatedBy": "HR300",
                 "includeUnrostered": false}""", hrToken);

        // 201 Created - payroll writes a new immutable revision.
        assertThat(send("POST", "/api/payroll/generate", """
                {"employeeId": "EMP300", "month": 10, "year": 2026, "generatedBy": "HR300"}""", hrToken)
                .status()).isEqualTo(201);

        Resp locked = send("PUT", "/api/attendance/EMP300/2026-10-20", """
                {"status": "PRESENT", "remarks": "Too late", "updatedBy": "HR300"}""", hrToken);
        assertThat(locked.status()).isEqualTo(400);
        assertThat(locked.body().get("message").asString()).contains("locked");

        Resp unlocked = send("POST", "/api/attendance/EMP300/unlock?month=2026-10", null, hrToken);
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(unlocked.body().get("unlockedDays").asInt()).isEqualTo(20);

        assertThat(send("PUT", "/api/attendance/EMP300/2026-10-20", """
                {"status": "PRESENT", "remarks": "Now allowed", "updatedBy": "HR300"}""", hrToken)
                .status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an employee correcting attendance is refused over HTTP - no ATTENDANCE_CORRECT permission")
    void roleIsEnforcedOverHttp() {
        send("POST", "/api/attendance/generate", """
                {"month": "2026-10", "userIds": ["EMP300"], "generatedBy": "HR300",
                 "includeUnrostered": false}""", hrToken);

        Resp refused = send("PUT", "/api/attendance/EMP300/2026-10-20", """
                {"status": "PRESENT", "remarks": "Marking myself present", "updatedBy": "EMP300"}""",
                employeeToken);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("message").asString()).contains("permission");
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
                    .uri(URI.create("http://localhost:" + port() + path))
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

    private String port() {
        return environment.getProperty("local.server.port");
    }

    private void saveEmployee(String userId, String code, String name, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode(code).employeeName(name)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(long id, LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(id).deviceId(1L).userId(EMPLOYEE).logDate(at).build();
    }
}

package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
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
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AttendanceRule} follows the exact per-company + global-default
 * pattern {@code SalaryRule} already has (see {@link EmployeeSalaryStructureHttpTest}
 * for that one) - a company's edit must never leak to another company, and
 * the values it holds must actually change what {@code POST
 * /api/attendance/generate} computes, not just sit there unused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendanceRuleHttpTest {

    private static final String PASSWORD = "Rule-Test-1";
    private static final YearMonth PERIOD = YearMonth.of(2026, 11);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company companyA;
    private Company companyB;
    private Shift shift;
    private String hrTokenA;
    private String hrTokenB;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        holidayRepository.deleteAll();
        companyRepository.deleteAll();

        companyA = companyRepository.save(Company.builder()
                .companyCode("RULE-CO-A").companyName("Rule Co A").status(RecordStatus.ACTIVE).build());
        companyB = companyRepository.save(Company.builder()
                .companyCode("RULE-CO-B").companyName("Rule Co B").status(RecordStatus.ACTIVE).build());

        shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build());

        saveEmployee("HRA001", companyA, Role.HR);
        saveEmployee("HRB001", companyB, Role.HR);

        hrTokenA = login("HRA001");
        hrTokenB = login("HRB001");
    }

    @Test
    @DisplayName("a new company starts on the global-default thresholds, matching what every company used before this was configurable")
    void defaultsAreSane() {
        Resp defaults = send("GET", "/api/attendance-rules", null, hrTokenA);
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.body().get("entryWindowBufferMinutes").asInt()).isEqualTo(60);
        assertThat(defaults.body().get("fullDayThresholdPercent").asDouble()).isEqualTo(75.0);
        assertThat(defaults.body().get("halfDayThresholdPercent").asDouble()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("halfDayThresholdPercent must be less than fullDayThresholdPercent")
    void validationRejectsHalfAtOrAboveFull() {
        Resp rejected = send("PUT", "/api/attendance-rules", """
                {"entryWindowBufferMinutes": 60, "fullDayThresholdPercent": 50, "halfDayThresholdPercent": 50}""",
                hrTokenA);
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString()).contains("less than");
    }

    @Test
    @DisplayName("updating one company's rule never leaks into another company's")
    void updateIsPerCompanyIsolated() {
        Resp updated = send("PUT", "/api/attendance-rules", """
                {"entryWindowBufferMinutes": 30, "fullDayThresholdPercent": 80, "halfDayThresholdPercent": 20}""",
                hrTokenA);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("halfDayThresholdPercent").asDouble()).isEqualTo(20.0);

        Resp stillDefaultForB = send("GET", "/api/attendance-rules", null, hrTokenB);
        assertThat(stillDefaultForB.body().get("halfDayThresholdPercent").asDouble()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("lowering halfDayThresholdPercent actually changes what a real generate call computes")
    void ruleChangeAffectsGeneratedAttendance() {
        Employee worker = saveEmployee("EMP-A01", companyA, Role.EMPLOYEE);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(worker.getUserId()).shiftDate(PERIOD.atDay(1)).shift(shift).weekOff(false).build());
        // 6:00 to 9:20 = 200 minutes span, minus the shift's 60 minute unpaid
        // break = 140 minutes worked, on an 8h (480 minute) shift: 29.2%.
        // Below the default 40% half-day threshold, so this starts as ABSENT.
        deviceLogRepository.save(punch(1, worker.getUserId(), PERIOD.atDay(1).atTime(6, 0)));
        deviceLogRepository.save(punch(2, worker.getUserId(), PERIOD.atDay(1).atTime(9, 20)));

        Resp beforeGenerate = send("POST", "/api/attendance/generate", """
                {"month": "2026-11", "userIds": ["EMP-A01"], "generatedBy": "HRA001"}""", hrTokenA);
        assertThat(beforeGenerate.status()).isEqualTo(200);

        Resp before = send("GET", "/api/attendance/EMP-A01/records?month=2026-11", null, hrTokenA);
        assertThat(before.body().get(0).get("status").asString()).isEqualTo("ABSENT");

        // Lower the half-day cutoff below 140/480 = 29.2%, and regenerate.
        assertThat(send("PUT", "/api/attendance-rules", """
                {"entryWindowBufferMinutes": 60, "fullDayThresholdPercent": 75, "halfDayThresholdPercent": 25}""",
                hrTokenA).status()).isEqualTo(200);

        Resp regenerated = send("POST", "/api/attendance/generate", """
                {"month": "2026-11", "userIds": ["EMP-A01"], "generatedBy": "HRA001"}""", hrTokenA);
        assertThat(regenerated.status()).isEqualTo(200);

        Resp after = send("GET", "/api/attendance/EMP-A01/records?month=2026-11", null, hrTokenA);
        assertThat(after.body().get(0).get("status").asString()).isEqualTo("HALF_DAY");
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

    private Employee saveEmployee(String userId, Company company, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
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

    private com.accusharp.hrms.entity.DeviceLog punch(long id, String userId, java.time.LocalDateTime at) {
        return com.accusharp.hrms.entity.DeviceLog.builder()
                .deviceLogId(id).deviceId(1L).userId(userId).logDate(at).build();
    }
}

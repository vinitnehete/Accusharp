package com.accusharp.hrms;

import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.PayrollStatus;
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
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the whole-company payroll batch's blast radius: one
 * employee with a roster/attendance gap must not roll back another
 * employee's payroll that was already generated in the same {@code
 * /api/payroll/generate-all} call - see {@code
 * PayrollController#generateForAll}'s Javadoc for why each employee now runs
 * as its own call to {@link com.accusharp.hrms.service.payroll.PayrollService#generate}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayrollGenerateAllHttpTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 11);
    private static final String GOOD_EMPLOYEE = "EMP400";
    private static final String BAD_EMPLOYEE = "EMP401";
    private static final String HR = "HR400";
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

        saveEmployee(HR, "EMP-HR-400", "HR Head", Role.HR);
        saveEmployee(GOOD_EMPLOYEE, "EMP-400", "Has Attendance", Role.EMPLOYEE);
        // BAD_EMPLOYEE deliberately has no roster and no attendance generated -
        // build() will throw "Attendance has not been generated" for this one.
        saveEmployee(BAD_EMPLOYEE, "EMP-401", "No Attendance", Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 10; day++) {
            roster.add(ShiftSchedule.builder().userId(GOOD_EMPLOYEE).shiftDate(PERIOD.atDay(day))
                    .shift(morning).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);

        List<DeviceLog> punches = new ArrayList<>();
        long id = 40_000;
        for (int day = 1; day <= 10; day++) {
            punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(GOOD_EMPLOYEE)
                    .logDate(PERIOD.atDay(day).atTime(6, 0)).build());
            punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(GOOD_EMPLOYEE)
                    .logDate(PERIOD.atDay(day).atTime(15, 0)).build());
        }
        deviceLogRepository.saveAll(punches);

        hrToken = login(HR);
        send("POST", "/api/attendance/generate", """
                {"month": "2026-11", "userIds": ["EMP400"], "generatedBy": "HR400"}""", hrToken);
    }

    @Test
    @DisplayName("generate-all reports one failure and one success instead of rolling back the whole batch")
    void oneEmployeesFailureDoesNotRollBackAnothersAlreadyGeneratedPayroll() {
        Resp response = send("POST",
                "/api/payroll/generate-all?month=" + PERIOD.getMonthValue() + "&year=" + PERIOD.getYear(),
                null, hrToken);

        // 3 active employees in the roster (HR400, EMP400, EMP401) - only
        // EMP400 has attendance generated in setUp(), so HR400 and EMP401
        // both fail the same "attendance not generated" guard as EMP401.
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("totalRows").asInt()).isEqualTo(3);
        assertThat(response.body().get("successCount").asInt()).isEqualTo(1);
        assertThat(response.body().get("failureCount").asInt()).isEqualTo(2);

        JsonNode succeeded = response.body().get("succeeded");
        assertThat(succeeded.size()).isEqualTo(1);
        assertThat(succeeded.get(0).get("employeeId").asString()).isEqualTo(GOOD_EMPLOYEE);

        JsonNode errors = response.body().get("errors");
        assertThat(errors.size()).isEqualTo(2);
        List<String> failedIdentifiers = new ArrayList<>();
        errors.forEach(e -> failedIdentifiers.add(e.get("identifier").asString()));
        assertThat(failedIdentifiers).containsExactlyInAnyOrder(BAD_EMPLOYEE, HR);
        errors.forEach(e -> assertThat(e.get("message").asString()).contains("Attendance has not been generated"));

        // The critical assertion: the good employee's payroll is actually
        // persisted, not rolled back by the bad employee's failure elsewhere
        // in the same batch call.
        assertThat(payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                GOOD_EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear(), PayrollStatus.GENERATED))
                .isPresent();
        assertThat(payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                BAD_EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear(), PayrollStatus.GENERATED))
                .isEmpty();
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
}

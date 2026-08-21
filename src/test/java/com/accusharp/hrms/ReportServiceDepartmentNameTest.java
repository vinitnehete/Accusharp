package com.accusharp.hrms;

import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
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
 * Correctness regression test for the ReportService.departmentNamesFor
 * refactor (batched lookup instead of one lazy load per row - flagged as an
 * N+1 in the audit's performance review): department names must still
 * resolve exactly the same as before, for an employee with a department and
 * one without.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportServiceDepartmentNameTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 12);
    private static final String HR = "RPT-HR";
    private static final String WITH_DEPT = "RPT-EMP1";
    private static final String NO_DEPT = "RPT-EMP2";
    private static final String PASSWORD = "Report-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private String hrToken;

    @BeforeEach
    void setUp() {
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        departmentRepository.deleteAll();
        shiftRepository.deleteAll();

        Department engineering = departmentRepository.save(Department.builder()
                .departmentCode("RPT-ENG").departmentName("Engineering").build());

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build());

        saveEmployee(HR, "HR", Role.HR, null);
        saveEmployee(WITH_DEPT, "Has Department", Role.EMPLOYEE, engineering);
        saveEmployee(NO_DEPT, "No Department", Role.EMPLOYEE, null);

        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        long id = 90_000;
        for (String userId : List.of(WITH_DEPT, NO_DEPT)) {
            for (int day = 1; day <= 5; day++) {
                roster.add(ShiftSchedule.builder().userId(userId).shiftDate(PERIOD.atDay(day))
                        .shift(morning).weekOff(false).build());
                punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(userId)
                        .logDate(PERIOD.atDay(day).atTime(6, 0)).build());
                punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(userId)
                        .logDate(PERIOD.atDay(day).atTime(15, 0)).build());
            }
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        hrToken = login(HR);
        send("POST", "/api/attendance/generate", """
                {"month": "2026-12", "userIds": ["RPT-EMP1", "RPT-EMP2"], "generatedBy": "RPT-HR"}""", hrToken);
    }

    @Test
    @DisplayName("the monthly attendance report resolves each employee's actual department, or 'Unassigned' when they have none")
    void monthlyAttendanceReportResolvesDepartmentNames() {
        Resp report = send("GET", "/api/reports/attendance/monthly?month=2026-12", null, hrToken);
        assertThat(report.status()).isEqualTo(200);

        JsonNode rows = report.body();
        String deptForEmp1 = null;
        String deptForEmp2 = null;
        for (JsonNode row : rows) {
            if (row.get("userId").asString().equals(WITH_DEPT)) {
                deptForEmp1 = row.get("departmentName").asString();
            }
            if (row.get("userId").asString().equals(NO_DEPT)) {
                deptForEmp2 = row.get("departmentName").asString();
            }
        }
        assertThat(deptForEmp1).isEqualTo("Engineering");
        assertThat(deptForEmp2).isEqualTo("Unassigned");
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

    private void saveEmployee(String userId, String name, Role role, Department department) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(name)
                .department(department)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}

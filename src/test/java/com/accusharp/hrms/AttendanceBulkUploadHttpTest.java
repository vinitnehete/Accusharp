package com.accusharp.hrms;

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
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulk attendance upload over real HTTP - the multipart CSV path and the
 * permission wiring, neither of which the service-level
 * {@code AttendanceExemptAndBulkUploadTest} can reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendanceBulkUploadHttpTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);
    private static final String DIRECTOR = "DIRH800";
    private static final String OTHER = "DIRH801";
    private static final String HR = "HRH800";
    private static final String PASSWORD = "Test-Password-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
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
    private String employeeToken;

    @BeforeEach
    void setUp() {
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        Shift general = shiftRepository.save(Shift.builder()
                .shiftCode("EXH-GEN").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HRH-800", "HR Head", Role.HR);
        saveEmployee(DIRECTOR, "EMP-DIRH-800", "Director One", Role.EMPLOYEE);
        saveEmployee(OTHER, "EMP-DIRH-801", "Director Two", Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        for (String userId : List.of(DIRECTOR, OTHER)) {
            for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
                LocalDate date = PERIOD.atDay(day);
                roster.add(ShiftSchedule.builder()
                        .userId(userId).shiftDate(date).shift(general)
                        .weekOff(date.getDayOfWeek() == DayOfWeek.SUNDAY).build());
            }
        }
        shiftScheduleRepository.saveAll(roster);

        hrToken = login(HR);
        employeeToken = login(DIRECTOR);
    }

    @Test
    @DisplayName("a two-line CSV declares a whole month for two people")
    void aCsvDeclaresAWholeMonthForTwoPeople() {
        String csv = """
                userId,fromDate,toDate,status,remarks
                %s,%s,%s,PRESENT,Management - exempt from biometric
                %s,%s,%s,,Management - exempt from biometric
                """.formatted(DIRECTOR, PERIOD.atDay(1), PERIOD.atEndOfMonth(),
                OTHER, PERIOD.atDay(1), PERIOD.atEndOfMonth());

        Resp response = uploadCsv(csv, hrToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("totalRows").asInt()).isEqualTo(2);
        assertThat(response.body().get("errors").size()).isZero();

        long sundays = PERIOD.atDay(1).datesUntil(PERIOD.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY).count();
        int workingDays = PERIOD.lengthOfMonth() - (int) sundays;

        // Both employees, every working day - and the second row proves a blank
        // status column defaults to PRESENT rather than failing the row.
        assertThat(response.body().get("daysApplied").asInt()).isEqualTo(workingDays * 2);
        assertThat(response.body().get("daysSkipped").asInt()).isEqualTo((int) sundays * 2);
        assertThat(dailyAttendanceRepository.findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                OTHER, PERIOD.atDay(1), PERIOD.atEndOfMonth())).hasSize(PERIOD.lengthOfMonth());
    }

    @Test
    @DisplayName("one bad row is reported without aborting the rest of the file")
    void oneBadRowDoesNotAbortTheFile() {
        String csv = """
                userId,fromDate,toDate,status,remarks
                %s,%s,%s,PRESENT,Exempt from biometric
                NOSUCHEMP,%s,%s,PRESENT,Exempt from biometric
                """.formatted(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(2),
                PERIOD.atDay(1), PERIOD.atDay(2));

        Resp response = uploadCsv(csv, hrToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("errors").size()).isEqualTo(1);
        assertThat(response.body().get("errors").get(0).get("identifier").asString()).isEqualTo("NOSUCHEMP");
        assertThat(response.body().get("daysApplied").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the JSON single-employee form works and reports skipped days")
    void theJsonFormReportsSkippedDays() {
        LocalDate sunday = PERIOD.atDay(1).datesUntil(PERIOD.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY).findFirst().orElseThrow();

        Resp response = send("POST", "/api/attendance/bulk-upload", """
                {"userId": "%s", "fromDate": "%s", "toDate": "%s", "status": "PRESENT",
                 "remarks": "Exempt from biometric"}"""
                .formatted(DIRECTOR, sunday.minusDays(1), sunday), hrToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("daysApplied").asInt()).isEqualTo(1);
        assertThat(response.body().get("skipped").size()).isEqualTo(1);
        assertThat(response.body().get("skipped").get(0).get("reason").asString())
                .isEqualTo("NON_WORKING_DAY");
    }

    @Test
    @DisplayName("a plain employee cannot upload attendance for themselves")
    void aPlainEmployeeCannotUpload() {
        Resp response = send("POST", "/api/attendance/bulk-upload", """
                {"userId": "%s", "fromDate": "%s", "toDate": "%s", "status": "PRESENT",
                 "remarks": "Marking myself present"}"""
                .formatted(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(5)), employeeToken);

        assertThat(response.status()).isEqualTo(403);
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate(DIRECTOR, PERIOD.atDay(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("HR cannot bulk-upload their own attendance even when they hold correction permission")
    void hrCannotUploadTheirOwnAttendance() {
        Resp response = send("POST", "/api/attendance/bulk-upload", """
                {"userId": "%s", "fromDate": "%s", "status": "PRESENT",
                 "remarks": "Marking myself present"}""".formatted(HR, PERIOD.atDay(1)), hrToken);

        assertThat(response.status()).isEqualTo(404);
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate(HR, PERIOD.atDay(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("a bulk upload records the authenticated actor rather than a supplied username")
    void aBulkUploadUsesTheAuthenticatedActor() {
        Resp response = send("POST", "/api/attendance/bulk-upload", """
                {"userId": "%s", "fromDate": "%s", "status": "PRESENT",
                 "remarks": "Device outage", "uploadedBy": "%s"}"""
                .formatted(DIRECTOR, PERIOD.atDay(1), DIRECTOR), hrToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate(DIRECTOR, PERIOD.atDay(1)))
                .get().satisfies(day -> assertThat(day.getUpdatedBy()).isEqualTo(HR));
    }

    @Test
    @DisplayName("a missing reason is rejected - attendance decides pay")
    void aMissingReasonIsRejected() {
        Resp response = send("POST", "/api/attendance/bulk-upload", """
                {"userId": "%s", "fromDate": "%s", "toDate": "%s", "status": "PRESENT"}"""
                .formatted(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(5)), hrToken);

        assertThat(response.status()).isEqualTo(400);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private Resp uploadCsv(String csv, String bearerToken) {
        String boundary = "----AccusharpTestBoundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"attendance.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv + "\r\n"
                + "--" + boundary + "--\r\n";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port() + "/api/attendance/bulk-upload/csv"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + bearerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.body() == null || response.body().isBlank()
                    ? null : objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            throw new IllegalStateException("CSV upload failed", ex);
        }
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
                    ? null : objectMapper.readTree(response.body());
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
                .grossSalary(new BigDecimal("80000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .attendanceExempt(role == Role.EMPLOYEE)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}

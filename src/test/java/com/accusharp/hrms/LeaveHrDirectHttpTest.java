package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
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
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HR/ADMIN entering an already-approved leave directly ({@code POST
 * /api/leaves/hr-create}) and its CSV bulk variant ({@code POST
 * /api/leaves/bulk-import}) - for backfilling days that already happened,
 * skipping the normal apply -> supervisor-endorse -> HR-approve chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveHrDirectHttpTest {

    private static final String PASSWORD = "Leave-HR-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company company;
    private String hrToken;
    private String empToken;

    @BeforeEach
    void setUp() {
        dailyAttendanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        holidayRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("LEAVE-HR-CO").companyName("Leave HR Co").status(RecordStatus.ACTIVE).build());

        saveEmployee("HR001", Role.HR);
        saveEmployee("EMP001", Role.EMPLOYEE);

        hrToken = login("HR001");
        empToken = login("EMP001");
    }

    @Test
    @DisplayName("HR entering a leave directly creates it already APPROVED, origin HR_DIRECT, and consumes balance immediately")
    void hrDirectCreateSucceedsAndConsumesBalanceImmediately() {
        Resp created = send("POST", "/api/leaves/hr-create", """
                {"userId": "EMP001", "leaveType": "CASUAL_LEAVE", "fromDate": "2026-11-02",
                 "toDate": "2026-11-03", "duration": "FULL_DAY", "reason": "Month-end backfill"}""", hrToken);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asString()).isEqualTo("APPROVED");
        assertThat(created.body().get("origin").asString()).isEqualTo("HR_DIRECT");
        assertThat(created.body().get("totalDays").asDouble()).isEqualTo(2.0);
        assertThat(created.body().get("approverId").asString()).isEqualTo("HR001");

        assertThat(leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType("EMP001", 2026, LeaveType.CASUAL_LEAVE)
                .orElseThrow().getUsed()).isEqualByComparingTo("2.0");
    }

    @Test
    @DisplayName("A direct entry hard-blocks the same way self-apply does once the paid balance is used up")
    void hrDirectCreateHardBlocksOnInsufficientBalance() {
        // Exactly the 12-day CASUAL_LEAVE quota, Nov 2 - Nov 13 inclusive.
        Resp usesFullQuota = send("POST", "/api/leaves/hr-create", """
                {"userId": "EMP001", "leaveType": "CASUAL_LEAVE", "fromDate": "2026-11-02",
                 "toDate": "2026-11-13", "duration": "FULL_DAY"}""", hrToken);
        assertThat(usesFullQuota.status()).isEqualTo(201);

        Resp overQuota = send("POST", "/api/leaves/hr-create", """
                {"userId": "EMP001", "leaveType": "CASUAL_LEAVE", "fromDate": "2026-11-16",
                 "toDate": "2026-11-16", "duration": "FULL_DAY"}""", hrToken);
        assertThat(overQuota.status()).isEqualTo(400);
        assertThat(overQuota.body().get("message").asString()).contains("Insufficient");
    }

    @Test
    @DisplayName("A plain EMPLOYEE cannot call hr-create - it requires LEAVE_APPROVE, same as final approval")
    void hrDirectCreateRequiresLeaveApprovePermission() {
        Resp refused = send("POST", "/api/leaves/hr-create", """
                {"userId": "EMP001", "leaveType": "CASUAL_LEAVE", "fromDate": "2026-11-02",
                 "toDate": "2026-11-02", "duration": "FULL_DAY"}""", empToken);
        assertThat(refused.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Bulk CSV import: independently-failable rows, same shape as the employee bulk import")
    void bulkCsvImportPartialFailure() {
        String csv = "userId,leaveType,fromDate,toDate,duration,reason\n"
                + "EMP001,CASUAL_LEAVE,2026-11-02,2026-11-02,FULL_DAY,Row 1 ok\n"
                + "EMP001,CASUAL_LEAVE,2026-11-02,2026-11-02,FULL_DAY,Row 2 overlaps row 1\n"
                + "EMP001,CASUAL_LEAVE,not-a-date,2026-11-05,FULL_DAY,Row 3 bad date\n"
                + "EMP001,ANNUAL_LEAVE,2026-11-06,2026-11-06,FULL_DAY,Row 4 unknown type\n";

        Resp result = sendMultipart("/api/leaves/bulk-import", "leaves.csv", csv, hrToken);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("totalRows").asInt()).isEqualTo(4);
        assertThat(result.body().get("successCount").asInt()).isEqualTo(1);
        assertThat(result.body().get("failureCount").asInt()).isEqualTo(3);
        assertThat(result.body().get("errors").get(0).get("message").asString()).contains("already covers part");
        assertThat(result.body().get("errors").get(1).get("message").asString()).contains("fromDate");
        assertThat(result.body().get("errors").get(2).get("message").asString()).contains("leaveType");
    }

    @Test
    @DisplayName("A leave entered directly by HR is picked up by attendance as ON_LEAVE, same as a normally-approved one")
    void hrDirectCreatedLeaveIsPickedUpByAttendance() {
        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build());
        LocalDate day = LocalDate.of(2026, 11, 5);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("EMP001").shiftDate(day).shift(shift).weekOff(false).build());

        Resp created = send("POST", "/api/leaves/hr-create", """
                {"userId": "EMP001", "leaveType": "CASUAL_LEAVE", "fromDate": "2026-11-05",
                 "toDate": "2026-11-05", "duration": "FULL_DAY", "reason": "Took the day off informally"}""",
                hrToken);
        assertThat(created.status()).isEqualTo(201);

        Resp preview = send("GET",
                "/api/attendance/EMP001?fromDate=2026-11-05&toDate=2026-11-05", null, hrToken);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().get(0).get("status").asString()).isEqualTo("ON_LEAVE");
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

    private Resp sendMultipart(String path, String fileName, String csvContent, String bearerToken) {
        try {
            String boundary = "----AccusharpTestBoundary" + System.nanoTime();
            String body = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n"
                    + csvContent + "\r\n"
                    + "--" + boundary + "--\r\n";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port() + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + bearerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = response.body() == null || response.body().isBlank()
                    ? null
                    : objectMapper.readTree(response.body());
            return new Resp(response.statusCode(), responseBody);
        } catch (Exception ex) {
            throw new IllegalStateException("multipart POST " + path + " failed", ex);
        }
    }

    private String port() {
        return environment.getProperty("local.server.port");
    }

    private void saveEmployee(String userId, Role role) {
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
        employeeRepository.save(employee);
    }
}

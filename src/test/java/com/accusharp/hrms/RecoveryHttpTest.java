package com.accusharp.hrms;

import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRecoveryRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.RecoveryTransactionRepository;
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
 * The recovery endpoints over real HTTP - what the service-level {@code
 * PayrollRecoveryTest} cannot cover: that the controller is wired, that the
 * new {@code RECOVERY_READ}/{@code RECOVERY_MANAGE} grants land on the right
 * roles, and that a whole-company {@code generate-all} run - which sends no
 * amounts for anybody - now recovers against outstanding balances instead of
 * the nothing it used to collect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecoveryHttpTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 12);
    private static final String EMPLOYEE = "RECH500";
    private static final String HR = "RECHHR500";
    private static final String PASSWORD = "Test-Password-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private EmployeeRecoveryRepository recoveryRepository;
    @Autowired private RecoveryTransactionRepository transactionRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private String hrToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        recoveryRepository.deleteAll();
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        Shift general = shiftRepository.save(Shift.builder()
                .shiftCode("RECH-GEN").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-RECH-HR", "Recovery HR", Role.HR);
        saveEmployee(EMPLOYEE, "EMP-RECH-500", "Loan Taker", Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        long id = 50_000;
        for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
            roster.add(ShiftSchedule.builder().userId(EMPLOYEE).shiftDate(PERIOD.atDay(day))
                    .shift(general).weekOff(false).build());
            punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(EMPLOYEE)
                    .logDate(PERIOD.atDay(day).atTime(9, 0)).build());
            punches.add(DeviceLog.builder().deviceLogId(id++).deviceId(1L).userId(EMPLOYEE)
                    .logDate(PERIOD.atDay(day).atTime(18, 0)).build());
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        hrToken = login(HR);
        employeeToken = login(EMPLOYEE);
        send("POST", "/api/attendance/generate", """
                {"month": "2026-12", "userIds": ["%s"], "generatedBy": "%s"}"""
                .formatted(EMPLOYEE, HR), hrToken);
    }

    @Test
    @DisplayName("a whole-company generate-all run recovers the loan instalment without being sent an amount")
    void generateAllRecoversAgainstTheLedger() {
        issueLoan("60000", "5000");

        Resp run = send("POST",
                "/api/payroll/generate-all?month=" + PERIOD.getMonthValue() + "&year=" + PERIOD.getYear(),
                null, hrToken);
        assertThat(run.status()).isEqualTo(200);

        JsonNode payroll = null;
        for (JsonNode row : run.body().get("succeeded")) {
            if (row.get("employeeId").asString().equals(EMPLOYEE)) {
                payroll = row;
            }
        }
        assertThat(payroll).as("the employee's payroll was generated").isNotNull();

        // The whole point: generate-all sends no amounts at all, and the
        // instalment is still recovered because the balance says it is due.
        assertThat(payroll.get("loanDeduction").asDouble()).isEqualTo(5000.00);
        assertThat(payroll.get("loanOpeningBalance").asDouble()).isEqualTo(60000.00);
        assertThat(payroll.get("loanClosingBalance").asDouble()).isEqualTo(55000.00);

        Resp statement = send("GET", "/api/recoveries/employee/" + EMPLOYEE + "/statement", null, hrToken);
        assertThat(statement.status()).isEqualTo(200);
        assertThat(statement.body().get("totalOutstanding").asDouble()).isEqualTo(55000.00);
    }

    @Test
    @DisplayName("the schedule endpoint tells the generate screen what is about to be recovered")
    void scheduleReportsWhatIsDue() {
        issueLoan("60000", "5000");

        Resp schedule = send("GET", "/api/recoveries/employee/" + EMPLOYEE + "/schedule"
                + "?month=" + PERIOD.getMonthValue() + "&year=" + PERIOD.getYear(), null, hrToken);

        assertThat(schedule.status()).isEqualTo(200);
        assertThat(schedule.body().get("loanDeduction").asDouble()).isEqualTo(5000.00);
        assertThat(schedule.body().get("totalOutstanding").asDouble()).isEqualTo(60000.00);
    }

    @Test
    @DisplayName("an ad-hoc fine and other deduction sent with a single generate are deducted and shown")
    void adHocFineAndOtherDeductionAreApplied() {
        Resp response = send("POST", "/api/payroll/generate", """
                {"employeeId": "%s", "month": %d, "year": %d, "fine": 500, "otherDeduction": 250}"""
                .formatted(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear()), hrToken);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("fine").asDouble()).isEqualTo(500.00);
        assertThat(response.body().get("otherDeduction").asDouble()).isEqualTo(250.00);
        assertThat(response.body().get("recoveryCarriedForward").asDouble()).isEqualTo(0.00);

        // Affordable in full, so nothing was raised to chase next month.
        assertThat(recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(EMPLOYEE)).isEmpty();

        Resp slip = send("GET", "/api/salary-slips/" + EMPLOYEE
                + "?month=" + PERIOD.getMonthValue() + "&year=" + PERIOD.getYear(), null, hrToken);
        assertThat(slip.status()).isEqualTo(200);
        List<String> labels = new ArrayList<>();
        slip.body().get("deductions").forEach(line -> labels.add(line.get("label").asString()));
        assertThat(labels).contains("Fine", "Other Deduction");
    }

    @Test
    @DisplayName("an employee can read their own balance but cannot issue a loan to themselves")
    void anEmployeeCanReadButNotManage() {
        issueLoan("60000", "5000");

        Resp own = send("GET", "/api/recoveries/employee/" + EMPLOYEE + "/statement", null, employeeToken);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body().get("totalOutstanding").asDouble()).isEqualTo(60000.00);

        Resp issue = send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": 100000}""".formatted(EMPLOYEE),
                employeeToken);
        assertThat(issue.status()).isEqualTo(403);

        // Read is self-service scoped, not company-wide, for a plain employee.
        Resp somebodyElse = send("GET", "/api/recoveries/employee/" + HR + "/statement", null, employeeToken);
        assertThat(somebodyElse.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the register lists what the company is owed")
    void theRegisterListsOutstandingBalances() {
        issueLoan("60000", "5000");

        Resp register = send("GET", "/api/recoveries", null, hrToken);

        assertThat(register.status()).isEqualTo(200);
        assertThat(register.body().size()).isEqualTo(1);
        assertThat(register.body().get(0).get("employeeId").asString()).isEqualTo(EMPLOYEE);
        assertThat(register.body().get(0).get("outstandingAmount").asDouble()).isEqualTo(60000.00);
        assertThat(register.body().get(0).get("remainingInstallments").asInt()).isEqualTo(12);
    }

    // ---- helpers -----------------------------------------------------------

    @Test
    @DisplayName("a loan can be registered for a past, current or future issue date without generated attendance or payroll")
    void aLoanCanBeRegisteredAnytime() {
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();

        for (LocalDate issuedOn : List.of(LocalDate.now().minusYears(1), LocalDate.now(), LocalDate.now().plusYears(1))) {
            Resp issued = send("POST", "/api/recoveries", """
                    {"employeeId": "%s", "type": "LOAN", "principalAmount": 12000,
                     "tenureMonths": 12, "issuedOn": "%s", "issuedBy": "FORGED"}"""
                    .formatted(EMPLOYEE, issuedOn), hrToken);

            assertThat(issued.status()).as(issuedOn.toString()).isEqualTo(201);
            assertThat(issued.body().get("issuedOn").asString()).isEqualTo(issuedOn.toString());
            YearMonth start = YearMonth.from(issuedOn).plusMonths(1);
            assertThat(issued.body().get("startMonth").asInt()).isEqualTo(start.getMonthValue());
            assertThat(issued.body().get("startYear").asInt()).isEqualTo(start.getYear());
            assertThat(issued.body().get("issuedBy").asString()).isEqualTo(HR);
        }

        assertThat(recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(EMPLOYEE)).hasSize(3);
        assertThat(payrollRepository.count()).isZero();
    }

    @Test
    @DisplayName("registering a loan after payroll was generated leaves the paid snapshot and ledger repayments unchanged")
    void aLoanRegisteredAfterPayrollDoesNotRewriteThePaidSnapshot() {
        Resp generated = send("POST", "/api/payroll/generate", """
                {"employeeId": "%s", "month": %d, "year": %d}"""
                .formatted(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear()), hrToken);
        assertThat(generated.status()).isEqualTo(201);
        long payrollId = generated.body().get("id").asLong();
        Payroll before = payrollRepository.findById(payrollId).orElseThrow();

        Resp issued = send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": 12000,
                 "installmentAmount": 1000, "issuedOn": "%s"}"""
                .formatted(EMPLOYEE, PERIOD.minusMonths(1).atDay(15)), hrToken);
        assertThat(issued.status()).isEqualTo(201);

        Payroll after = payrollRepository.findById(payrollId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getRevision()).isEqualTo(before.getRevision());
        assertThat(after.getLoanDeduction()).isEqualByComparingTo("0");
        assertThat(after.getLoanOpeningBalance()).isEqualByComparingTo("0");
        assertThat(after.getLoanClosingBalance()).isEqualByComparingTo("0");
        assertThat(after.getNetSalary()).isEqualByComparingTo(before.getNetSalary());
        assertThat(payrollRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.findAllByRecoveryIdOrderByIdAsc(issued.body().get("id").asLong()))
                .singleElement().satisfies(transaction -> assertThat(transaction.getPayrollId()).isNull());
    }

    @Test
    @DisplayName("an explicit start period must supply its month and year together")
    void aPartialStartPeriodIsRejected() {
        for (String start : List.of("\"startMonth\": 12", "\"startYear\": 2026")) {
            Resp issued = send("POST", "/api/recoveries", """
                    {"employeeId": "%s", "type": "LOAN", "principalAmount": 12000,
                     "issuedOn": "2026-12-01", %s}""".formatted(EMPLOYEE, start), hrToken);
            assertThat(issued.status()).isEqualTo(400);
            assertThat(issued.body().get("message").asString()).contains("startMonth", "startYear");
        }
        assertThat(recoveryRepository.count()).isZero();
    }

    @Test
    @DisplayName("recovery cannot start before the loan's issue month, but the issue month itself is allowed")
    void theLoanCannotBeRecoveredBeforeItIsIssued() {
        Resp invalid = send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": 12000,
                 "issuedOn": "2027-01-15", "startMonth": 12, "startYear": 2026}"""
                .formatted(EMPLOYEE), hrToken);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(recoveryRepository.count()).isZero();

        Resp sameMonth = send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": 12000,
                 "issuedOn": "2027-01-15", "startMonth": 1, "startYear": 2027}"""
                .formatted(EMPLOYEE), hrToken);
        assertThat(sameMonth.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("principal and instalments must still be positive after money is rounded to two places")
    void amountsThatRoundToZeroAreRejected() {
        for (String amounts : List.of("\"principalAmount\": 0.001",
                "\"principalAmount\": 100, \"installmentAmount\": 0.001",
                "\"principalAmount\": 0.01, \"tenureMonths\": 600")) {
            Resp issued = send("POST", "/api/recoveries", """
                    {"employeeId": "%s", "type": "LOAN", %s}""".formatted(EMPLOYEE, amounts), hrToken);
            assertThat(issued.status()).as(amounts).isEqualTo(400);
        }
        assertThat(recoveryRepository.count()).isZero();
    }

    @Test
    @DisplayName("bulk issuing applies the same term validation as a single request and keeps valid rows")
    void bulkIssueValidatesEveryRowIndependently() {
        String csv = "employeeId,type,principalAmount,tenureMonths,startMonth,startYear,issuedOn\n"
                + EMPLOYEE + ",LOAN,12000,0,,,2026-01-15\n"
                + EMPLOYEE + ",LOAN,12000,12,,,2026-01-15\n"
                + EMPLOYEE + ",LOAN,12000,12,12,,2026-01-15\n";
        Resp result = sendMultipart("/api/recoveries/bulk", csv);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("totalRows").asInt()).isEqualTo(3);
        assertThat(result.body().get("successCount").asInt()).isEqualTo(1);
        assertThat(result.body().get("failureCount").asInt()).isEqualTo(2);
        assertThat(result.body().get("errors").get(0).get("message").asString()).contains("tenureMonths");
        assertThat(recoveryRepository.count()).isEqualTo(1);
    }

    private Resp sendMultipart(String path, String csv) {
        try {
            String boundary = "----RecoveryTestBoundary" + System.nanoTime();
            String body = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"recoveries.csv\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n" + csv + "\r\n"
                    + "--" + boundary + "--\r\n";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port() + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + hrToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            throw new IllegalStateException("multipart POST " + path + " failed", ex);
        }
    }

    private void issueLoan(String principal, String installment) {
        Resp response = send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": %s, "installmentAmount": %s,
                 "issuedOn": "%s", "startMonth": %d, "startYear": %d, "reason": "Home repair"}"""
                .formatted(EMPLOYEE, principal, installment, PERIOD.atDay(1),
                        PERIOD.getMonthValue(), PERIOD.getYear()), hrToken);
        if (response.status() != 201) {
            throw new IllegalStateException("Issue failed: " + response.body());
        }
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
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}

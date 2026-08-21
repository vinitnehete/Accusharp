package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two companies, two HR users, over real HTTP - proving Company A's HR
 * cannot reach Company B's data by id, no matter how the request is shaped.
 * This is the literal scenario the tenant-isolation work exists for: "GET
 * /api/employees/500 must not return that employee" when 500 belongs to a
 * different company.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantIsolationHttpTest {

    private static final String PASSWORD = "Tenant-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company companyA;
    private Company companyB;
    private Employee hrA;
    private Employee employeeB;
    private String hrAToken;
    private String hrBToken;

    @BeforeEach
    void setUp() {
        holidayRepository.deleteAll();
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        departmentRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        shiftRepository.deleteAll();
        payrollRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        companyA = companyRepository.save(Company.builder()
                .companyCode("TENANT-A").companyName("Tenant A Industries")
                .status(RecordStatus.ACTIVE).build());
        companyB = companyRepository.save(Company.builder()
                .companyCode("TENANT-B").companyName("Tenant B Industries")
                .status(RecordStatus.ACTIVE).build());

        hrA = saveEmployee("HRA001", "Tenant A HR", Role.HR, companyA);
        saveEmployee("HRB001", "Tenant B HR", Role.HR, companyB);
        employeeB = saveEmployee("EMPB001", "Tenant B Worker", Role.EMPLOYEE, companyB);

        hrAToken = login("HRA001");
        hrBToken = login("HRB001");
    }

    @Test
    @DisplayName("Company A's HR cannot read, update or delete Company B's holiday by id, "
            + "and cannot plant a holiday into Company B by naming its id")
    void holidayCrossTenantAccessIsRejected() {
        // hrA tries to create a holiday IN Company B by naming its id - lands in Company A instead.
        String createBody = """
                {"companyId": %d, "holidayName": "Sneaky Holiday", "holidayDate": "2026-12-25",
                 "optionalHoliday": false}""".formatted(companyB.getId());
        Resp created = send("POST", "/api/holidays", createBody, hrAToken);
        assertThat(created.status()).isEqualTo(201);
        Long holidayId = created.body().get("id").asLong();
        // (Response omits company - see Holiday's @JsonIgnore Javadoc - so verify via the DB directly.)
        Holiday stored = holidayRepository.findById(holidayId).orElseThrow();
        assertThat(stored.getCompany().getId()).isEqualTo(companyA.getId());

        // Company B's own HR, using its own id space, cannot see, update or delete that holiday.
        Resp getAsB = send("GET", "/api/holidays", null, hrBToken);
        assertThat(getAsB.status()).isEqualTo(200);
        assertThat(getAsB.body().size()).isZero();

        Resp updateAsB = send("PUT", "/api/holidays/" + holidayId, """
                {"holidayName": "Overwritten", "holidayDate": "2026-12-25", "optionalHoliday": true}""", hrBToken);
        assertThat(updateAsB.status()).isEqualTo(404);

        Resp deleteAsB = send("DELETE", "/api/holidays/" + holidayId, null, hrBToken);
        assertThat(deleteAsB.status()).isEqualTo(404);

        // The record survives, untouched, for Company A.
        assertThat(holidayRepository.findById(holidayId)).isPresent();
    }

    @Test
    @DisplayName("Company A's HR cannot correct or unlock Company B's attendance by userId")
    void attendanceCrossTenantWriteIsRejected() {
        Resp correct = send("PUT", "/api/attendance/EMPB001/2026-01-15", """
                {"status": "PRESENT", "remarks": "Marking present", "updatedBy": "HRA001"}""", hrAToken);
        assertThat(correct.status()).isEqualTo(404);

        Resp unlock = send("POST", "/api/attendance/EMPB001/unlock?month=2026-01", null, hrAToken);
        assertThat(unlock.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Company A's HR gets 404, not the record, for Company B's employee by id or userId")
    void employeeCrossTenantReadIsRejected() {
        Resp byId = send("GET", "/api/employees/" + employeeB.getId(), null, hrAToken);
        assertThat(byId.status()).isEqualTo(404);

        Resp byUserId = send("GET", "/api/employees/by-user-id/EMPB001", null, hrAToken);
        assertThat(byUserId.status()).isEqualTo(404);

        // Sanity check: the same HR can read their own company's employee fine.
        Resp ownCompany = send("GET", "/api/employees/by-user-id/HRA001", null, hrAToken);
        assertThat(ownCompany.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Company A's HR cannot reset Company B's employee's password")
    void employeePasswordResetCrossTenantIsRejected() {
        Resp reset = send("POST", "/api/employees/" + employeeB.getId() + "/reset-password", null, hrAToken);
        assertThat(reset.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Company A's HR gets 404 for Company B's payroll history by employee id")
    void payrollCrossTenantReadIsRejected() {
        Resp history = send("GET", "/api/payroll/employee/EMPB001", null, hrAToken);
        assertThat(history.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("two companies can independently use the same employeeCode - it's unique per company, not globally")
    void employeeCodeIsUniquePerCompanyNotGlobally() {
        // hrA already exists as employeeCode "EMP-HRA001" (see saveEmployee). Company B
        // using that exact same code for one of its own employees must succeed.
        String body = """
                {
                  "userId": "EMPB002", "employeeCode": "EMP-HRA001", "employeeName": "Same Code, Other Company",
                  "status": "PERMANENT", "role": "EMPLOYEE",
                  "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0
                }
                """;
        Resp created = send("POST", "/api/employees", body, hrBToken);
        assertThat(created.status()).isEqualTo(201);

        // But a second employee inside the SAME company reusing an existing code is still rejected.
        String duplicateWithinCompany = """
                {
                  "userId": "EMPB003", "employeeCode": "EMP-HRA001", "employeeName": "Duplicate In Same Company",
                  "status": "PERMANENT", "role": "EMPLOYEE",
                  "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0
                }
                """;
        Resp rejected = send("POST", "/api/employees", duplicateWithinCompany, hrBToken);
        assertThat(rejected.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("an employee create request cannot plant the new hire in another company")
    void employeeCreateIgnoresClientSuppliedCompanyId() {
        String body = """
                {
                  "userId": "EMPA002", "employeeCode": "EMP-A-002", "employeeName": "Sneaky Hire",
                  "companyId": %d, "status": "PERMANENT", "role": "EMPLOYEE",
                  "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0
                }
                """.formatted(companyB.getId());

        Resp created = send("POST", "/api/employees", body, hrAToken);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("employee").get("companyName").asString())
                .isEqualTo(companyA.getCompanyName());
    }

    @Test
    @DisplayName("GET /api/companies only shows the caller's own company, and /{id} 404s for another")
    void companyListAndGetAreScoped() {
        Resp list = send("GET", "/api/companies", null, hrAToken);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(1);
        assertThat(list.body().get(0).get("companyCode").asString()).isEqualTo("TENANT-A");

        Resp otherCompany = send("GET", "/api/companies/" + companyB.getId(), null, hrAToken);
        assertThat(otherCompany.status()).isEqualTo(404);

        Resp ownCompany = send("GET", "/api/companies/" + companyA.getId(), null, hrAToken);
        assertThat(ownCompany.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("updating the salary rule creates a company-specific row and leaves other companies on the default")
    void salaryRuleUpdateIsPerCompany() {
        Resp before = send("GET", "/api/salary-rules", null, hrAToken);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().get("basicDaPercent").asDouble()).isEqualTo(50.0);

        String update = """
                {"basicDaPercent": 60, "basicDaMinimumThreshold": 0, "hraPercent": 40, "conveyancePercent": 10,
                 "educationPercent": 10, "pfPercent": 12, "esicPercent": 0.75, "esicWageCeiling": 21000,
                 "ptUpperThreshold": 10001, "ptUpperAmount": 200, "ptLowerThreshold": 7501, "ptLowerAmount": 175,
                 "dayWiseDaysInMonth": 26, "standardHoursPerDay": 8, "overtimeRateMultiplier": 1.0,
                 "mlwfAmount": 0}""";
        Resp updated = send("PUT", "/api/salary-rules", update, hrAToken);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("basicDaPercent").asDouble()).isEqualTo(60.0);

        // The global default (what an as-yet-uncustomized company still reads) is untouched.
        assertThat(salaryRuleService.getActiveRuleForCompany(null).getBasicDaPercent())
                .isEqualByComparingTo(new BigDecimal("50"));
        // Company B never customized its own rule, so it still resolves to the untouched default too.
        assertThat(salaryRuleService.getActiveRuleForCompany(companyB).getBasicDaPercent())
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    @DisplayName("Company A's HR cannot read, update or delete Company B's department by id, "
            + "and Company B's list doesn't include it")
    void departmentCrossTenantAccessIsRejected() {
        Resp created = send("POST", "/api/departments", """
                {"departmentCode": "TENANT-DEPT", "departmentName": "Tenant A Only Dept"}""", hrAToken);
        assertThat(created.status()).isEqualTo(201);
        Long departmentId = created.body().get("id").asLong();

        Resp listAsB = send("GET", "/api/departments", null, hrBToken);
        assertThat(listAsB.status()).isEqualTo(200);
        assertThat(listAsB.body().size()).isZero();

        Resp getAsB = send("GET", "/api/departments/" + departmentId, null, hrBToken);
        assertThat(getAsB.status()).isEqualTo(404);

        Resp updateAsB = send("PUT", "/api/departments/" + departmentId, """
                {"departmentCode": "TENANT-DEPT", "departmentName": "Overwritten"}""", hrBToken);
        assertThat(updateAsB.status()).isEqualTo(404);

        Resp deleteAsB = send("DELETE", "/api/departments/" + departmentId, null, hrBToken);
        assertThat(deleteAsB.status()).isEqualTo(404);

        // The record survives, untouched, for Company A.
        Resp getAsA = send("GET", "/api/departments/" + departmentId, null, hrAToken);
        assertThat(getAsA.status()).isEqualTo(200);
        assertThat(getAsA.body().get("departmentName").asString()).isEqualTo("Tenant A Only Dept");
    }

    @Test
    @DisplayName("Company A's HR cannot edit or delete Company B's shift - "
            + "specifically closing the 'edit is not guarded at all' gap from the audit")
    void shiftCrossTenantEditIsRejected() {
        Resp created = send("POST", "/api/shifts", """
                {"shiftCode": "TENANT-SHIFT", "shiftName": "Tenant A Only Shift",
                 "startTime": "09:00:00", "endTime": "17:00:00", "workingHours": 8,
                 "breakMinutes": 30, "graceMinutes": 10, "overtimeWindowMinutes": 240}""", hrAToken);
        assertThat(created.status()).isEqualTo(201);
        Long shiftId = created.body().get("id").asLong();

        Resp listAsB = send("GET", "/api/shifts", null, hrBToken);
        assertThat(listAsB.status()).isEqualTo(200);
        assertThat(listAsB.body().size()).isZero();

        // The edit path the audit found completely unguarded.
        Resp editAsB = send("PUT", "/api/shifts/" + shiftId, """
                {"shiftCode": "TENANT-SHIFT", "shiftName": "Corrupted",
                 "startTime": "00:00:00", "endTime": "23:59:00", "workingHours": 23,
                 "breakMinutes": 0, "graceMinutes": 0, "overtimeWindowMinutes": 0}""", hrBToken);
        assertThat(editAsB.status()).isEqualTo(404);

        Resp deleteAsB = send("DELETE", "/api/shifts/" + shiftId, null, hrBToken);
        assertThat(deleteAsB.status()).isEqualTo(404);

        Resp getAsA = send("GET", "/api/shifts/" + shiftId, null, hrAToken);
        assertThat(getAsA.status()).isEqualTo(200);
        assertThat(getAsA.body().get("shiftName").asString()).isEqualTo("Tenant A Only Shift");
    }

    @Test
    @DisplayName("GET /api/reports/payroll only returns the caller's own company's payroll rows")
    void payrollReportIsScopedPerCompany() {
        payrollRepository.save(minimalPayroll("HRA001"));
        payrollRepository.save(minimalPayroll("EMPB001"));

        Resp report = send("GET", "/api/reports/payroll?month=3&year=2031", null, hrAToken);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().size()).isEqualTo(1);
        assertThat(report.body().get(0).get("userId").asString()).isEqualTo("HRA001");
    }

    @Test
    @DisplayName("Company A's HR cannot delete Company B's shift schedule by naming its userId")
    void shiftScheduleDeleteRangeCrossTenantIsRejected() {
        Resp delete = send("DELETE",
                "/api/shift-schedules/EMPB001?fromDate=2026-01-01&toDate=2026-01-31", null, hrAToken);
        assertThat(delete.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Company A's HR cannot swap shifts with Company B's employee - "
            + "closing the one gap the audit found in ShiftSchedulingService.swap")
    void shiftScheduleSwapCrossTenantIsRejected() {
        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("TENANT-SWAP-SHIFT").shiftName("Tenant Swap Shift")
                .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(17, 0))
                .workingHours(8).breakMinutes(30).graceMinutes(10).overtimeWindowMinutes(240)
                .build());
        LocalDate date = LocalDate.of(2026, 6, 15);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("HRA001").shiftDate(date).shift(shift).weekOff(false).assignedBy("HRA001").build());
        ShiftSchedule targetSchedule = shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("EMPB001").shiftDate(date).shift(shift).weekOff(false).assignedBy("HRB001").build());

        Resp swap = send("POST", "/api/shift-schedules/swap", """
                {"firstUserId": "HRA001", "secondUserId": "EMPB001", "shiftDate": "2026-06-15",
                 "assignedBy": "HRA001"}""", hrAToken);
        assertThat(swap.status()).isEqualTo(404);

        // Untouched: Company B's schedule still points at the original shift.
        ShiftSchedule stillOriginal = shiftScheduleRepository.findById(targetSchedule.getId()).orElseThrow();
        assertThat(stillOriginal.getShift().getId()).isEqualTo(shift.getId());
        assertThat(stillOriginal.getUserId()).isEqualTo("EMPB001");
    }

    @Test
    @DisplayName("Company A's HR cannot overwrite Company B's employee's leave quota")
    void leaveBalanceSetQuotaCrossTenantIsRejected() {
        Resp setQuota = send("PUT",
                "/api/leave-balances/EMPB001?year=2026&leaveType=SICK_LEAVE&quota=999", null, hrAToken);
        assertThat(setQuota.status()).isEqualTo(404);

        // No balance row was ever created for Company B by this attempt.
        assertThat(leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType(
                "EMPB001", 2026, LeaveType.SICK_LEAVE)).isEmpty();
    }

    @Test
    @DisplayName("Company A's HR cannot approve, reject or cancel Company B's leave request")
    void leaveDecisionCrossTenantIsRejected() {
        LeaveRequest crossCompanyLeave = leaveRequestRepository.save(LeaveRequest.builder()
                .userId("EMPB001").leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(LocalDate.of(2026, 6, 1)).toDate(LocalDate.of(2026, 6, 1))
                .duration(LeaveDuration.FULL_DAY).totalDays(new BigDecimal("1.0"))
                .status(LeaveStatus.PENDING).origin(LeaveOrigin.SELF_SERVICE).appliedAt(java.time.Instant.now())
                .build());

        // approverId is required by validation even though the controller always
        // overwrites it with the caller's own username before it reaches the service.
        String decisionBody = """
                {"approverId": "ignored", "comments": "attempted cross-tenant decision"}""";
        assertThat(send("POST", "/api/leaves/" + crossCompanyLeave.getId() + "/supervisor-approve",
                decisionBody, hrAToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/leaves/" + crossCompanyLeave.getId() + "/approve",
                decisionBody, hrAToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/leaves/" + crossCompanyLeave.getId() + "/reject",
                decisionBody, hrAToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/leaves/" + crossCompanyLeave.getId() + "/cancel",
                decisionBody, hrAToken).status()).isEqualTo(404);

        // Untouched: still PENDING, no approver recorded, balance never consumed.
        LeaveRequest stillPending = leaveRequestRepository.findById(crossCompanyLeave.getId()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(LeaveStatus.PENDING);
        assertThat(stillPending.getApproverId()).isNull();
    }

    @Test
    @DisplayName("Company A's HR cannot generate or regenerate payroll for Company B's employee, "
            + "and generate() 404s rather than leaking existence via 409")
    void payrollGenerateCrossTenantIsRejected() {
        String generateBody = """
                {"employeeId": "EMPB001", "month": 4, "year": 2031}""";
        Resp generate = send("POST", "/api/payroll/generate", generateBody, hrAToken);
        assertThat(generate.status()).isEqualTo(404);

        // Even though Company B already has a GENERATED row for this exact period -
        // this must still 404, not 409, or it would leak that fact across tenants.
        Payroll existing = payrollRepository.save(minimalPayroll("EMPB001"));
        Resp generateAgain = send("POST", "/api/payroll/generate", """
                {"employeeId": "EMPB001", "month": 3, "year": 2031}""", hrAToken);
        assertThat(generateAgain.status()).isEqualTo(404);

        Resp regenerate = send("POST", "/api/payroll/regenerate", """
                {"employeeId": "EMPB001", "month": 3, "year": 2031}""", hrAToken);
        assertThat(regenerate.status()).isEqualTo(404);

        // Untouched: still GENERATED, not flipped to SUPERSEDED.
        Payroll stillGenerated = payrollRepository.findById(existing.getId()).orElseThrow();
        assertThat(stillGenerated.getStatus()).isEqualTo(PayrollStatus.GENERATED);
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

    private Employee saveEmployee(String userId, String name, Role role, Company company) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(name)
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

    /** A payroll row with just enough set to satisfy the entity's NOT NULL columns - the generation flow itself is out of scope here. */
    private Payroll minimalPayroll(String employeeId) {
        BigDecimal zero = BigDecimal.ZERO;
        return Payroll.builder()
                .employeeId(employeeId).month(3).year(2031).revision(1).status(PayrollStatus.GENERATED)
                .daysInMonth(31).workingDays(26)
                .presentDays(zero).paidLeaveDays(zero).lopDays(zero).payableDays(zero)
                .earnGrossSalary(zero).totalEarnings(zero).totalDeduction(zero).netSalary(zero)
                .generatedAt(java.time.Instant.now())
                .build();
    }
}

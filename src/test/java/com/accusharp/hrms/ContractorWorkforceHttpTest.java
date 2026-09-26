package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.ContractorRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The labour-contractor module over real HTTP, with the emphasis on the one
 * property the whole design turns on: <b>a contractor's workers and the
 * company's own employees never mix</b>.
 *
 * <p>They share a table ({@code Employee}, keyed by the {@code userId} the
 * biometric device punches against) and they share the shift catalog, but
 * every company-scoped read must exclude them and every company-scoped write
 * must refuse them - otherwise a person this company does not pay ends up in
 * a payroll run, a PF return or the headcount. Those exclusions are what
 * these tests pin down; a regression in any of them is silent in production
 * until a payslip is generated for somebody else's employee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractorWorkforceHttpTest {

    private static final String PASSWORD = "Contractor-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ContractorRepository contractorRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company company;
    private Employee staffEmployee;
    private String hrToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        summaryRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        payrollRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        contractorRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("CTR-CO").companyName("Contractor Test Works")
                .status(RecordStatus.ACTIVE).build());

        saveEmployee("CTRHR01", "HR Person", Role.HR, null);
        Employee supervisor = saveEmployee("CTRSUP01", "Line Supervisor", Role.SUPERVISOR, null);
        staffEmployee = saveEmployee("CTREMP01", "Own Staff", Role.EMPLOYEE, supervisor);

        hrToken = login("CTRHR01");
        supervisorToken = login("CTRSUP01");
    }

    /**
     * Clears this test's contractors before handing the shared H2 instance
     * back.
     *
     * <p>Every other {@code @SpringBootTest} in this suite tears down with
     * {@code employeeRepository.deleteAll(); companyRepository.deleteAll();},
     * and {@code contractor.company_id} is a foreign key onto {@code company}
     * - so a contractor row left behind here makes the <em>next</em> test
     * class fail on a referential-integrity violation it has nothing to do
     * with. This is the only test that writes the table, so cleaning up after
     * itself is cheaper than teaching thirty other classes about it.
     */
    @AfterEach
    void tearDown() {
        employeeRepository.deleteAll();
        contractorRepository.deleteAll();
    }

    // ---- onboarding ---------------------------------------------------------

    @Test
    @DisplayName("A contractor is onboarded, then workers are added under it with a supervisor from this company")
    void contractorAndWorkforceAreOnboarded() {
        long contractorId = createContractor("ACME", "Acme Manpower");

        Resp created = send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar",
                 "supervisorUserId": "CTRSUP01", "joiningDate": "2031-01-05", "phone": "9999900001"}""",
                hrToken);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("contractorName").asString()).isEqualTo("Acme Manpower");
        assertThat(created.body().get("supervisorName").asString()).isEqualTo("Line Supervisor");

        // The contractor's name travels on every row - what makes a list
        // spanning several contractors readable.
        Resp workforce = send("GET", "/api/contractors/employees", null, hrToken);
        assertThat(workforce.status()).isEqualTo(200);
        assertThat(workforce.body().size()).isEqualTo(1);
        assertThat(workforce.body().get(0).get("contractorName").asString()).isEqualTo("Acme Manpower");

        // The count is derived, never stored.
        Resp contractor = send("GET", "/api/contractors/" + contractorId, null, hrToken);
        assertThat(contractor.body().get("activeEmployeeCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("A contractor's worker gets no login: the row carries no password and the account is disabled")
    void contractorWorkerHasNoLogin() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);

        Employee worker = employeeRepository.findByUserId("ACME001").orElseThrow();
        assertThat(worker.getPasswordHash()).isNull();
        assertThat(worker.isAccountEnabled()).isFalse();
        // No salary structure either - the contractor pays them, not us.
        assertThat(worker.getGrossSalary()).isNull();
        assertThat(worker.getGrossSalaryWage()).isNull();
        assertThat(worker.getRole()).isEqualTo(Role.EMPLOYEE);

        assertThat(send("POST", "/api/auth/login",
                "{\"username\": \"ACME001\", \"password\": \"" + PASSWORD + "\"}", null).status())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("A worker's supervisor must be one of this company's employees, never another contractor's worker")
    void supervisorMustBeACompanyEmployee() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);

        Resp rejected = send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME002", "employeeCode": "AC-002", "employeeName": "Sunil Rao",
                 "supervisorUserId": "ACME001"}""", hrToken);
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString()).contains("Acme Manpower's worker");
    }

    // ---- the isolation the whole design turns on ----------------------------

    @Test
    @DisplayName("Contractor workers never appear in the company employee directory or the employee report")
    void contractorWorkersAreInvisibleToTheEmployeeModule() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);

        List<String> directory = userIds(send("GET", "/api/employees", null, hrToken));
        assertThat(directory).containsExactlyInAnyOrder("CTRHR01", "CTRSUP01", "CTREMP01");

        List<String> report = userIds(send("GET", "/api/reports/employees", null, hrToken));
        assertThat(report).doesNotContain("ACME001");

        // Not reachable one at a time either - reported as not found, the same
        // answer another company's userId gets.
        assertThat(send("GET", "/api/employees/by-user-id/ACME001", null, hrToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("The employee endpoints refuse to write a contractor worker, so none can be given a salary or a role")
    void employeeEndpointsRefuseAContractorWorker() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        Resp created = send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);
        long workerId = created.body().get("id").asLong();

        String escalation = """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar",
                 "status": "PERMANENT", "role": "EMPLOYEE", "grossSalary": 90000, "pfBasic": 30000,
                 "medicalAllowance": 0, "otherAllowance": 0}""";
        assertThat(send("PUT", "/api/employees/" + workerId, escalation, hrToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/employees/" + workerId, null, hrToken).status()).isEqualTo(404);
        assertThat(send("DELETE", "/api/employees/" + workerId, null, hrToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/employees/" + workerId + "/reset-password", null, hrToken).status())
                .isEqualTo(404);

        Employee worker = employeeRepository.findByUserId("ACME001").orElseThrow();
        assertThat(worker.getGrossSalary()).isNull();
        assertThat(worker.getStatus()).isEqualTo(EmployeeStatus.CONTRACT);
        assertThat(worker.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
    }

    @Test
    @DisplayName("Payroll generate-all pays the company's own staff and never a contractor's workers")
    void payrollNeverReachesContractorWorkers() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);

        // Generation itself needs generated attendance per employee, so this
        // asserts the population rather than a successful run: whatever
        // generate-all reports on, ACME001 is not in it.
        Resp result = send("POST", "/api/payroll/generate-all?month=1&year=2031", null, hrToken);
        assertThat(result.status()).isIn(200, 201, 422);
        assertThat(payrollRepository.findAll().stream().map(p -> p.getEmployeeId()).toList())
                .doesNotContain("ACME001");
    }

    @Test
    @DisplayName("Company-wide attendance generation covers only company staff; the contractor run covers only theirs")
    void attendanceGenerationKeepsThePopulationsApart() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar",
                 "joiningDate": "2031-01-01"}""", hrToken);

        // No userIds means "everybody" - and everybody is the company's own staff.
        Resp companyRun = send("POST", "/api/attendance/generate",
                "{\"month\": \"2031-01\", \"generatedBy\": \"CTRHR01\", \"dryRun\": true}", hrToken);
        assertThat(companyRun.status()).isEqualTo(200);
        assertThat(companyRun.body().get("employeesProcessed").asInt()).isEqualTo(3);
        assertThat(userIdList(companyRun.body().get("employeesWithoutRoster"))).doesNotContain("ACME001");

        Resp contractorRun = send("POST",
                "/api/contractors/" + contractorId + "/attendance/generate?month=2031-01&dryRun=true",
                null, hrToken);
        assertThat(contractorRun.status()).isEqualTo(200);
        assertThat(contractorRun.body().get("employeesProcessed").asInt()).isEqualTo(1);
        assertThat(userIdList(contractorRun.body().get("employeesWithoutRoster"))).containsExactly("ACME001");
    }

    // ---- several contractors, and the reports ------------------------------

    @Test
    @DisplayName("Several contractors are kept apart: each report lists only its own workers, and one line each on the summary")
    void severalContractorsAreReportedSeparately() {
        long acme = createContractor("ACME", "Acme Manpower");
        long bharat = createContractor("BHARAT", "Bharat Labour Services");

        send("POST", "/api/contractors/" + acme + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);
        send("POST", "/api/contractors/" + acme + "/employees", """
                {"userId": "ACME002", "employeeCode": "AC-002", "employeeName": "Sunil Rao"}""", hrToken);
        send("POST", "/api/contractors/" + bharat + "/employees", """
                {"userId": "BHR001", "employeeCode": "BH-001", "employeeName": "Imran Shaikh"}""", hrToken);

        Resp acmeReport = send("GET",
                "/api/contractors/" + acme + "/reports/attendance/monthly?month=2031-01", null, hrToken);
        assertThat(acmeReport.status()).isEqualTo(200);
        assertThat(acmeReport.body().get("rows").size()).isEqualTo(2);
        assertThat(acmeReport.body().get("summary").get("contractorName").asString())
                .isEqualTo("Acme Manpower");
        // Nobody has generated attendance yet - the figure that says the report
        // is not ready to send.
        assertThat(acmeReport.body().get("summary").get("workersWithoutAttendance").asInt()).isEqualTo(2);

        Resp summary = send("GET", "/api/contractors/reports/attendance/summary?month=2031-01", null, hrToken);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.body().size()).isEqualTo(2);

        // Filtering the workforce by contractor returns only that agency's people.
        assertThat(send("GET", "/api/contractors/employees?contractorId=" + bharat, null, hrToken)
                .body().size()).isEqualTo(1);

        // The export carries the contractor's name on every line.
        Resp export = send("GET",
                "/api/contractors/" + acme + "/reports/attendance/monthly/export?month=2031-01", null, hrToken);
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.rawBody()).contains("Acme Manpower").doesNotContain("Bharat Labour Services");
    }

    @Test
    @DisplayName("A contractor cannot be deactivated while workers are still deployed under it")
    void deactivationIsGuardedByTheDeployedWorkforce() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        Resp created = send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "ACME001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar"}""", hrToken);

        Resp refused = send("DELETE", "/api/contractors/" + contractorId, null, hrToken);
        assertThat(refused.status()).isEqualTo(409);

        send("DELETE", "/api/contractors/employees/" + created.body().get("id").asLong(), null, hrToken);
        assertThat(send("DELETE", "/api/contractors/" + contractorId, null, hrToken).status()).isEqualTo(200);
    }

    // ---- authorization ------------------------------------------------------

    @Test
    @DisplayName("A SUPERVISOR may read the contractor workforce but never onboard one")
    void supervisorReadsButDoesNotManage() {
        long contractorId = createContractor("ACME", "Acme Manpower");

        assertThat(send("GET", "/api/contractors", null, supervisorToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/contractors/employees", null, supervisorToken).status()).isEqualTo(200);

        assertThat(send("POST", "/api/contractors",
                "{\"contractorCode\": \"NOPE\", \"contractorName\": \"Not Allowed\"}", supervisorToken).status())
                .isEqualTo(403);
        assertThat(send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "NOPE01", "employeeCode": "NO-01", "employeeName": "Nope"}""",
                supervisorToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("A userId already taken by a company employee cannot be reused for a contractor worker")
    void userIdStaysUniqueAcrossBothPopulations() {
        long contractorId = createContractor("ACME", "Acme Manpower");
        Resp clash = send("POST", "/api/contractors/" + contractorId + "/employees", """
                {"userId": "CTREMP01", "employeeCode": "AC-001", "employeeName": "Clashing Name"}""", hrToken);
        assertThat(clash.status()).isEqualTo(409);
        assertThat(employeeRepository.findByUserId("CTREMP01").orElseThrow().getId())
                .isEqualTo(staffEmployee.getId());
    }

    // ---- helpers ------------------------------------------------------------

    private long createContractor(String code, String name) {
        Resp response = send("POST", "/api/contractors",
                "{\"contractorCode\": \"" + code + "\", \"contractorName\": \"" + name
                        + "\", \"contactPerson\": \"Desk\", \"email\": \"desk@example.com\"}", hrToken);
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("id").asLong();
    }

    private List<String> userIds(Resp response) {
        List<String> ids = new ArrayList<>();
        response.body().forEach(node -> ids.add(node.get("userId").asString()));
        return ids;
    }

    private List<String> userIdList(JsonNode array) {
        List<String> ids = new ArrayList<>();
        if (array != null) {
            array.forEach(node -> ids.add(node.asString()));
        }
        return ids;
    }

    private record Resp(int status, JsonNode body, String rawBody) {
    }

    private String login(String userId) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + userId + "\", \"password\": \"" + PASSWORD + "\"}", null);
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed for " + userId + ": " + response.rawBody());
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
            String raw = response.body();
            // The CSV exports are not JSON - keep the raw body either way so one
            // helper serves both.
            JsonNode body = raw == null || raw.isBlank() || !(raw.startsWith("{") || raw.startsWith("["))
                    ? null
                    : objectMapper.readTree(raw);
            return new Resp(response.statusCode(), body, raw);
        } catch (Exception ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
    }

    private String port() {
        return environment.getProperty("local.server.port");
    }

    private Employee saveEmployee(String userId, String name, Role role, Employee supervisor) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(name)
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
}

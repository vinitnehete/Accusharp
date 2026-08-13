package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.PayrollRepository;
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
 * One company, several roles, over real HTTP - proving "view only my own
 * data" (SECURITY.md's self-service scoping) within a single company. This
 * is the intra-company companion to {@link TenantIsolationHttpTest}: that
 * class proves Company A cannot reach Company B; this one proves a plain
 * EMPLOYEE cannot reach a coworker's data, and a SUPERVISOR is limited to
 * their own directly-supervised team.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SelfServiceScopingHttpTest {

    private static final String PASSWORD = "SelfService-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Employee empA;
    private Employee empB;
    private Employee supX;
    private Employee teamMember;
    private String empAToken;
    private String empBToken;
    private String supXToken;

    @BeforeEach
    void setUp() {
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        payrollRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("SELF-SVC").companyName("Self Service Industries")
                .status(RecordStatus.ACTIVE).build());

        empA = saveEmployee("SSA001", "Emp A", Role.EMPLOYEE, company, null);
        empB = saveEmployee("SSB001", "Emp B", Role.EMPLOYEE, company, null);
        supX = saveEmployee("SSSUP01", "Supervisor X", Role.SUPERVISOR, company, null);
        teamMember = saveEmployee("SSTEAM01", "Team Member", Role.EMPLOYEE, company, supX);

        empAToken = login("SSA001");
        empBToken = login("SSB001");
        supXToken = login("SSSUP01");
    }

    @Test
    @DisplayName("An EMPLOYEE can read their own record but not a coworker's, and the list shows only themselves")
    void employeeDirectoryIsSelfServiceRestricted() {
        assertThat(send("GET", "/api/employees/by-user-id/SSB001", null, empAToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/employees/by-user-id/SSA001", null, empAToken).status()).isEqualTo(200);

        Resp list = send("GET", "/api/employees", null, empAToken);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(1);
        assertThat(list.body().get(0).get("userId").asString()).isEqualTo("SSA001");
    }

    @Test
    @DisplayName("A SUPERVISOR's directory list is themselves plus their own team, not the whole company")
    void supervisorDirectoryIsRestrictedToOwnTeam() {
        Resp list = send("GET", "/api/employees", null, supXToken);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(2);

        assertThat(send("GET", "/api/employees/by-user-id/SSTEAM01", null, supXToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/employees/by-user-id/SSB001", null, supXToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Only a supervisor themselves (or HR/ADMIN) may fetch their team's roster")
    void getTeamIsRestrictedToOwnerOrPrivileged() {
        assertThat(send("GET", "/api/employees/SSSUP01/team", null, empAToken).status()).isEqualTo(404);

        Resp asSupervisor = send("GET", "/api/employees/SSSUP01/team", null, supXToken);
        assertThat(asSupervisor.status()).isEqualTo(200);
        assertThat(asSupervisor.body().size()).isEqualTo(1);
        assertThat(asSupervisor.body().get(0).get("userId").asString()).isEqualTo("SSTEAM01");
    }

    @Test
    @DisplayName("Attendance reads are self-service restricted: own data yes, a coworker's or a non-report's no")
    void attendanceReadsAreSelfServiceRestricted() {
        String range = "?fromDate=2031-02-01&toDate=2031-02-01";
        assertThat(send("GET", "/api/attendance/SSB001" + range, null, empAToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/attendance/SSA001" + range, null, empAToken).status()).isEqualTo(200);

        assertThat(send("GET", "/api/attendance/SSTEAM01" + range, null, supXToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/attendance/SSB001" + range, null, supXToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Shift roster reads are self-service restricted the same way")
    void shiftRosterIsSelfServiceRestricted() {
        assertThat(send("GET", "/api/shift-schedules/SSB001", null, empAToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/shift-schedules/SSA001", null, empAToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/shift-schedules/SSTEAM01", null, supXToken).status()).isEqualTo(200);
        assertThat(send("GET", "/api/shift-schedules/SSB001", null, supXToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("An EMPLOYEE cannot file leave as another coworker, but a supervisor may file for their own team")
    void leaveApplyBlocksImpersonationButAllowsSupervisorOnBehalf() {
        String asOther = """
                {"userId": "SSB001", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-02-10",
                 "toDate": "2031-02-10", "duration": "FULL_DAY"}""";
        assertThat(send("POST", "/api/leaves", asOther, empAToken).status()).isEqualTo(404);

        String asSelf = """
                {"userId": "SSA001", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-02-11",
                 "toDate": "2031-02-11", "duration": "FULL_DAY"}""";
        assertThat(send("POST", "/api/leaves", asSelf, empAToken).status()).isEqualTo(201);

        String onBehalfOfReport = """
                {"userId": "SSTEAM01", "leaveType": "CASUAL_LEAVE", "fromDate": "2031-02-12",
                 "toDate": "2031-02-12", "duration": "FULL_DAY"}""";
        assertThat(send("POST", "/api/leaves", onBehalfOfReport, supXToken).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("Leave reads (by id and history) are self-service restricted")
    void leaveReadsAreSelfServiceRestricted() {
        LeaveRequest othersLeave = leaveRequestRepository.save(LeaveRequest.builder()
                .userId("SSB001").leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(LocalDate.of(2031, 3, 1)).toDate(LocalDate.of(2031, 3, 1))
                .duration(LeaveDuration.FULL_DAY).totalDays(new BigDecimal("1.0"))
                .status(LeaveStatus.PENDING).appliedAt(java.time.Instant.now())
                .build());

        assertThat(send("GET", "/api/leaves/" + othersLeave.getId(), null, empAToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/leaves/" + othersLeave.getId(), null, empBToken).status()).isEqualTo(200);

        assertThat(send("GET", "/api/leaves/employee/SSB001", null, empAToken).status()).isEqualTo(404);
        Resp ownHistory = send("GET", "/api/leaves/employee/SSA001", null, empAToken);
        assertThat(ownHistory.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Leave balance reads are self-service restricted")
    void leaveBalanceIsSelfServiceRestricted() {
        assertThat(send("GET", "/api/leave-balances/SSB001?year=2031", null, empAToken).status()).isEqualTo(404);
        assertThat(send("GET", "/api/leave-balances/SSA001?year=2031", null, empAToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Salary slips are self-service restricted - the widest blast-radius endpoint in the audit")
    void salarySlipIsSelfServiceRestricted() {
        payrollRepository.save(minimalPayroll("SSA001"));
        payrollRepository.save(minimalPayroll("SSB001"));

        assertThat(send("GET", "/api/salary-slips/SSB001?month=5&year=2031", null, empAToken).status())
                .isEqualTo(404);
        assertThat(send("GET", "/api/salary-slips/SSA001?month=5&year=2031", null, empAToken).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("A supervisor's payroll-period list is filtered to their own team, not the whole company")
    void payrollPeriodIsFilteredForSupervisor() {
        payrollRepository.save(minimalPayroll("SSTEAM01"));
        payrollRepository.save(minimalPayroll("SSB001"));

        Resp period = send("GET", "/api/payroll?month=5&year=2031", null, supXToken);
        assertThat(period.status()).isEqualTo(200);
        assertThat(period.body().size()).isEqualTo(1);
        assertThat(period.body().get(0).get("employeeId").asString()).isEqualTo("SSTEAM01");
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

    private Employee saveEmployee(String userId, String name, Role role, Company company, Employee supervisor) {
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

    private Payroll minimalPayroll(String employeeId) {
        BigDecimal zero = BigDecimal.ZERO;
        return Payroll.builder()
                .employeeId(employeeId).month(5).year(2031).revision(1).status(PayrollStatus.GENERATED)
                .daysInMonth(31).workingDays(26)
                .presentDays(zero).paidLeaveDays(zero).lopDays(zero).payableDays(zero)
                .earnGrossSalary(zero).totalEarnings(zero).totalDeduction(zero).netSalary(zero)
                .generatedAt(java.time.Instant.now())
                .build();
    }
}

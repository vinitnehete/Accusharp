package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.*;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Recovery money follows the same PAY_READ, tenant and managed-subject boundaries as payroll. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecoveryAccessHttpTest {

    private static final String PASSWORD = "Recovery-Access-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ContractorRepository contractorRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmployeeCustomRoleRepository employeeCustomRoleRepository;
    @Autowired private CustomRolePermissionRepository customRolePermissionRepository;
    @Autowired private CustomRoleRepository customRoleRepository;
    @Autowired private EmployeeRecoveryRepository recoveryRepository;
    @Autowired private RecoveryTransactionRepository transactionRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private String adminToken;
    private String hrToken;
    private String supervisorToken;
    private String employeeToken;
    private Company company;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder().companyCode("REC-ACCESS")
                .companyName("Recovery Access Co").status(RecordStatus.ACTIVE).build());
        saveEmployee("RAADMIN", Role.ADMIN, null);
        saveEmployee("RAHR", Role.HR, null);
        Employee supervisor = saveEmployee("RASUP", Role.SUPERVISOR, null);
        saveEmployee("RAEMP", Role.EMPLOYEE, supervisor);
        adminToken = login("RAADMIN");
        hrToken = login("RAHR");
        supervisorToken = login("RASUP");
        employeeToken = login("RAEMP");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("team record scope does not expose recoveries without PAY_READ, while employees keep their own balances")
    void teamMoneyRequiresPayRead() {
        assertThat(issue("RAEMP", hrToken).status()).isEqualTo(201);
        for (String suffix : List.of("", "/statement", "/schedule?month=12&year=2026")) {
            assertThat(send("GET", "/api/recoveries/employee/RAEMP" + suffix, null, supervisorToken).status())
                    .as(suffix).isEqualTo(403);
        }
        assertThat(send("GET", "/api/recoveries", null, supervisorToken).body().size()).isZero();
        assertThat(send("GET", "/api/recoveries/employee/RAEMP/statement", null, employeeToken).status())
                .isEqualTo(200);

        grantToSupervisor("PAY_READ");
        assertThat(send("GET", "/api/recoveries/employee/RAEMP/statement", null, supervisorToken).status())
                .isEqualTo(200);
        assertThat(send("GET", "/api/recoveries", null, supervisorToken).body().size()).isEqualTo(1);
        assertThat(send("GET", "/api/recoveries/employee/RAHR/statement", null, supervisorToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a custom recovery manager can change managed employees only, never their own or another team's loans")
    void recoveryManageDoesNotWidenTheSubjectScope() {
        grantToSupervisor("RECOVERY_MANAGE");
        Resp teamLoan = issue("RAEMP", supervisorToken);
        assertThat(teamLoan.status()).isEqualTo(201);
        long teamId = teamLoan.body().get("id").asLong();
        assertThat(send("PUT", "/api/recoveries/" + teamId,
                "{\"installmentAmount\": 200}", supervisorToken).status()).isEqualTo(200);
        assertThat(issue("RASUP", supervisorToken).status()).isEqualTo(403);
        assertThat(issue("RAHR", supervisorToken).status()).isEqualTo(404);

        Resp outsideLoan = issue("RAHR", adminToken);
        assertThat(outsideLoan.status()).isEqualTo(201);
        long outsideId = outsideLoan.body().get("id").asLong();
        assertThat(send("PUT", "/api/recoveries/" + outsideId,
                "{\"installmentAmount\": 200}", supervisorToken).status()).isEqualTo(404);
        assertThat(send("POST", "/api/recoveries/" + outsideId + "/waive",
                "{\"amount\": 100, \"remarks\": \"Adjustment request\"}", supervisorToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("HR cannot issue their own loan and company accounts or contractor workers cannot become recovery subjects")
    void recoverySubjectsAreCompanyStaffOtherThanTheCaller() {
        assertThat(issue("RAHR", hrToken).status()).isEqualTo(403);
        assertThat(issue("RAADMIN", hrToken).status()).isEqualTo(400);

        Contractor contractor = contractorRepository.save(Contractor.builder().company(company)
                .contractorCode("RA-CON").contractorName("Agency").build());
        Employee worker = saveEmployee("RAWORKER", Role.EMPLOYEE, null);
        worker.setContractor(contractor);
        employeeRepository.save(worker);
        assertThat(issue("RAWORKER", hrToken).status()).isEqualTo(404);
        assertThat(recoveryRepository.count()).isZero();
    }

    private Resp issue(String employeeId, String token) {
        return send("POST", "/api/recoveries", """
                {"employeeId": "%s", "type": "LOAN", "principalAmount": 1200,
                 "installmentAmount": 100, "issuedOn": "2026-11-01"}""".formatted(employeeId), token);
    }

    private void grantToSupervisor(String permission) {
        Resp role = send("POST", "/api/roles", "{\"name\": \"Recovery " + permission + "\"}", adminToken);
        assertThat(role.status()).isEqualTo(201);
        long id = role.body().get("id").asLong();
        assertThat(send("PUT", "/api/roles/" + id + "/permissions",
                "{\"permissionCodes\": [\"" + permission + "\"]}", adminToken).status()).isEqualTo(200);
        assertThat(send("POST", "/api/roles/" + id + "/employees/RASUP", null, adminToken).status()).isEqualTo(204);
    }

    private Employee saveEmployee(String userId, Role role, Employee supervisor) {
        return employeeRepository.save(Employee.builder().userId(userId).employeeCode("C-" + userId)
                .employeeName(userId).company(company).supervisor(supervisor)
                .role(role).status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE)
                .joiningDate(LocalDate.of(2022, 1, 1)).grossSalary(new BigDecimal("30000"))
                .pfBasic(new BigDecimal("9000")).medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO)
                .passwordHash(passwordEncoder.encode(PASSWORD)).accountEnabled(true)
                .accountLocked(false).failedLoginAttempts(0).build());
    }

    private String login(String userId) {
        Resp response = send("POST", "/api/auth/login",
                "{\"username\": \"" + userId + "\", \"password\": \"" + PASSWORD + "\"}", null);
        assertThat(response.status()).isEqualTo(200);
        return response.body().get("accessToken").asString();
    }

    private record Resp(int status, JsonNode body) {
    }

    private Resp send(String method, String path, String json, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                    .header("Content-Type", "application/json").method(method, json == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.body().isBlank() ? null : objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
    }

    private void clean() {
        transactionRepository.deleteAll();
        recoveryRepository.deleteAll();
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        contractorRepository.deleteAll();
        if (company != null) {
            companyRepository.deleteById(company.getId());
            company = null;
        }
    }
}

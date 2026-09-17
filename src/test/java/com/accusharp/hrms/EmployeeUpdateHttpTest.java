package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.CustomRolePermissionRepository;
import com.accusharp.hrms.repository.CustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.repository.SalaryStructureRevisionRepository;
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
 * {@code PUT /api/employees/{id}} with a field left out. Two fields used to be
 * reset to a default rather than kept when omitted, each a silent state change
 * nobody asked for: {@code role} fell back to EMPLOYEE, demoting a supervisor
 * (and so hiding their team from them), and {@code recordStatus} fell back to
 * ACTIVE, reactivating an employee who had been deactivated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeUpdateHttpTest {

    private static final String PASSWORD = "Update-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private SalaryStructureRevisionRepository salaryStructureRevisionRepository;
    @Autowired private EmployeeCustomRoleRepository employeeCustomRoleRepository;
    @Autowired private CustomRolePermissionRepository customRolePermissionRepository;
    @Autowired private CustomRoleRepository customRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hrToken;

    @BeforeEach
    void setUp() {
        employeeCustomRoleRepository.deleteAll();
        customRolePermissionRepository.deleteAll();
        customRoleRepository.deleteAll();
        salaryRevisionRepository.deleteAll();
        salaryStructureRevisionRepository.deleteAll();
        employeeRepository.findAll().forEach(employee -> {
            employee.setSupervisor(null);
            employeeRepository.save(employee);
        });
        employeeRepository.deleteAll();
        companyRepository.deleteAll();

        Company company = companyRepository.save(Company.builder()
                .companyCode("UPD-CO").companyName("Update Co").status(RecordStatus.ACTIVE).build());

        employeeRepository.save(Employee.builder()
                .userId("UPDHR001").employeeCode("UPD-HR-1").employeeName("Update HR")
                .company(company).status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE)
                .role(Role.HR).joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("10000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());

        hrToken = login("UPDHR001");
    }

    @Test
    @DisplayName("an update that leaves out role keeps the employee's current role")
    void updateWithoutRoleKeepsCurrentRole() {
        long id = create("UPD-SUP", "\"role\": \"SUPERVISOR\",");

        Resp updated = send("PUT", "/api/employees/" + id, body("UPD-SUP", "Renamed Supervisor", ""), hrToken);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("employeeName").asString()).isEqualTo("Renamed Supervisor");
        assertThat(updated.body().get("role").asString()).isEqualTo("SUPERVISOR");
        assertThat(employeeRepository.findByUserId("UPD-SUP").orElseThrow().getRole()).isEqualTo(Role.SUPERVISOR);
    }

    @Test
    @DisplayName("an update that names a role still changes it")
    void updateWithRoleChangesIt() {
        long id = create("UPD-DEMOTE", "\"role\": \"SUPERVISOR\",");

        Resp updated = send("PUT", "/api/employees/" + id,
                body("UPD-DEMOTE", "Demoted", "\"role\": \"EMPLOYEE\","), hrToken);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("role").asString()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("a new employee created without a role is still an EMPLOYEE")
    void createWithoutRoleDefaultsToEmployee() {
        create("UPD-PLAIN", "");

        assertThat(employeeRepository.findByUserId("UPD-PLAIN").orElseThrow().getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(employeeRepository.findByUserId("UPD-PLAIN").orElseThrow().getRecordStatus())
                .isEqualTo(RecordStatus.ACTIVE);
    }

    @Test
    @DisplayName("an update that leaves out recordStatus does not reactivate a deactivated employee")
    void updateWithoutRecordStatusKeepsDeactivatedEmployeeInactive() {
        long id = create("UPD-LEFT", "");
        assertThat(send("DELETE", "/api/employees/" + id, null, hrToken).status()).isEqualTo(200);

        Resp updated = send("PUT", "/api/employees/" + id, body("UPD-LEFT", "Former Employee", ""), hrToken);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("recordStatus").asString()).isEqualTo("INACTIVE");
        assertThat(employeeRepository.findByUserId("UPD-LEFT").orElseThrow().getRecordStatus())
                .isEqualTo(RecordStatus.INACTIVE);
    }

    // ---- helpers -----------------------------------------------------------

    private record Resp(int status, JsonNode body) {
    }

    private long create(String userId, String extraFields) {
        Resp created = send("POST", "/api/employees", body(userId, userId + " Name", extraFields), hrToken);
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("employee").get("id").asLong();
    }

    private String body(String userId, String name, String extraFields) {
        return """
                {"userId": "%s", "employeeCode": "%s-C", "employeeName": "%s", "status": "PERMANENT", %s
                 "grossSalary": 20000, "pfBasic": 8000, "medicalAllowance": 1000, "otherAllowance": 0}"""
                .formatted(userId, userId, name, extraFields);
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
                    .uri(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
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
}

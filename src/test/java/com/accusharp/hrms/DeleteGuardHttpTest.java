package com.accusharp.hrms;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DesignationRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.service.CompanyService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the two missing in-use guards the audit's database
 * review flagged: Designation delete had none at all (unlike Department/
 * Category), and Company delete relied entirely on a generic FK-violation
 * 409 rather than an explicit, actionable check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeleteGuardHttpTest {

    private static final String PASSWORD = "Guard-Test-1";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DesignationRepository designationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CompanyService companyService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Company company;
    private String hrToken;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        designationRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("GUARD-CO").companyName("Guard Co").status(RecordStatus.ACTIVE).build());

        Employee hr = Employee.builder()
                .userId("GUARDHR001").employeeCode("EMP-GUARDHR001").employeeName("Guard HR")
                .company(company).status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE)
                .role(Role.HR).joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("20000")).pfBasic(new BigDecimal("8000"))
                .medicalAllowance(new BigDecimal("1000")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).passwordHash(passwordEncoder.encode(PASSWORD))
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        employeeRepository.save(hr);

        hrToken = login("GUARDHR001");
    }

    @Test
    @DisplayName("a designation still assigned to an employee cannot be deleted")
    void designationWithEmployeesCannotBeDeleted() {
        Designation designation = designationRepository.save(Designation.builder()
                .designationCode("GUARD-DESIG").designationName("Guarded Role").company(company).build());

        Employee assigned = employeeRepository.findByUserId("GUARDHR001").orElseThrow();
        assigned.setDesignation(designation);
        employeeRepository.save(assigned);

        Resp delete = send("DELETE", "/api/designations/" + designation.getId(), null, hrToken);
        assertThat(delete.status()).isEqualTo(409);

        assertThat(designationRepository.findById(designation.getId())).isPresent();
    }

    @Test
    @DisplayName("a designation with no employees can be deleted normally")
    void designationWithNoEmployeesCanBeDeleted() {
        Designation designation = designationRepository.save(Designation.builder()
                .designationCode("GUARD-DESIG-2").designationName("Unused Role").company(company).build());

        Resp delete = send("DELETE", "/api/designations/" + designation.getId(), null, hrToken);
        assertThat(delete.status()).isEqualTo(204);

        assertThat(designationRepository.findById(designation.getId())).isEmpty();
    }

    @Test
    @DisplayName("a company with employees still on the books cannot be deleted")
    void companyWithEmployeesCannotBeDeleted() {
        assertThatThrownBy(() -> companyService.delete(company.getId()))
                .isInstanceOf(ConflictException.class);

        assertThat(companyRepository.findById(company.getId())).isPresent();
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
}

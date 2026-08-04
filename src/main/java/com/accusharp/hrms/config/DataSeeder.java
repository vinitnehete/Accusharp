package com.accusharp.hrms.config;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.DesignationRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Seeds the masters an empty database cannot work without - the four standard
 * shifts and the salary rule - plus a small demo org so the API is explorable
 * on first run.
 *
 * <p>Idempotent: nothing is written if the data already exists. Disable with
 * {@code hrms.seed.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DataSeeder {

    private final CompanyRepository companyRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryRuleService salaryRuleService;
    private final SalaryCalculationService salaryCalculationService;

    @Bean
    ApplicationRunner seedReferenceData() {
        return args -> {
            salaryRuleService.getActiveRule();
            seedShifts();
            seedOrganisation();
        };
    }

    /** The four shifts from the specification; admins may add custom ones. */
    private void seedShifts() {
        createShift("MORNING", "Morning", LocalTime.of(6, 0), LocalTime.of(15, 0));
        createShift("GENERAL", "General", LocalTime.of(9, 0), LocalTime.of(18, 0));
        createShift("EVENING", "Evening", LocalTime.of(14, 0), LocalTime.of(23, 0));
        // Ends before it starts, so the engine treats it as crossing midnight.
        createShift("NIGHT", "Night", LocalTime.of(18, 0), LocalTime.of(8, 0));
    }

    private void createShift(String code, String name, LocalTime start, LocalTime end) {
        if (shiftRepository.existsByShiftCode(code)) {
            return;
        }
        shiftRepository.save(Shift.builder()
                .shiftCode(code)
                .shiftName(name)
                .startTime(start)
                .endTime(end)
                .workingHours(8)
                .breakMinutes(60)
                .graceMinutes(15)
                .overtimeWindowMinutes(240)
                .build());
        log.info("seed.shift code={}", code);
    }

    private void seedOrganisation() {
        if (employeeRepository.count() > 0) {
            return;
        }

        Company company = companyRepository.findByCompanyCode("ACC")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyCode("ACC")
                        .companyName("Accusharp Industries")
                        .address("Pune, Maharashtra")
                        .phone("020-00000000")
                        .email("hr@accusharp.example")
                        .status(RecordStatus.ACTIVE)
                        .build()));

        Department production = department("PROD", "Production");
        Department admin = department("ADMIN", "Administration");

        Designation operator = designation("OPR", "Machine Operator");
        Designation manager = designation("MGR", "Manager");

        Employee hr = saveEmployee("HR001", "EMP-HR-001", "Meera Joshi", company, admin, manager,
                null, EmployeeStatus.PERMANENT, Role.HR, new BigDecimal("45000"),
                new BigDecimal("15000"), LocalDate.of(2019, 4, 1), false);

        Employee supervisor = saveEmployee("SUP001", "EMP-SUP-001", "Rakesh Patil", company, production,
                manager, hr, EmployeeStatus.PERMANENT, Role.SUPERVISOR, new BigDecimal("38000"),
                new BigDecimal("13000"), LocalDate.of(2020, 6, 15), false);

        saveEmployee("EMP001", "EMP-001", "Sunil Kadam", company, production, operator, supervisor,
                EmployeeStatus.PERMANENT, Role.EMPLOYEE, new BigDecimal("22000"),
                new BigDecimal("9000"), LocalDate.of(2022, 1, 10), true);

        saveEmployee("EMP002", "EMP-002", "Anita Shinde", company, production, operator, supervisor,
                EmployeeStatus.DAY_WISE, Role.EMPLOYEE, new BigDecimal("18000"),
                new BigDecimal("7500"), LocalDate.of(2023, 3, 5), true);

        log.info("seed.organisation company={} employees={}", company.getCompanyCode(),
                employeeRepository.count());
    }

    private Department department(String code, String name) {
        return departmentRepository.findByDepartmentCode(code)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .departmentCode(code).departmentName(name).build()));
    }

    private Designation designation(String code, String name) {
        return designationRepository.findByDesignationCode(code)
                .orElseGet(() -> designationRepository.save(Designation.builder()
                        .designationCode(code).designationName(name).build()));
    }

    private Employee saveEmployee(String userId, String code, String name, Company company,
                                  Department department, Designation designation, Employee supervisor,
                                  EmployeeStatus status, Role role, BigDecimal gross, BigDecimal pfBasic,
                                  LocalDate joiningDate, boolean overtimeEligible) {

        Employee employee = Employee.builder()
                .userId(userId)
                .employeeCode(code)
                .employeeName(name)
                .company(company)
                .department(department)
                .designation(designation)
                .supervisor(supervisor)
                .joiningDate(joiningDate)
                .status(status)
                .recordStatus(RecordStatus.ACTIVE)
                .role(role)
                .email(userId.toLowerCase() + "@accusharp.example")
                .grossSalary(gross)
                .pfBasic(pfBasic)
                .medicalAllowance(new BigDecimal("1250"))
                .otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(overtimeEligible)
                .build();

        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        return employeeRepository.saveAll(List.of(employee)).getFirst();
    }
}

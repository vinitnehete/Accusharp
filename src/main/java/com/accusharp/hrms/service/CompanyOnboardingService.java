package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.CompanyOnboardingRequest;
import com.accusharp.hrms.dto.CompanyOnboardingResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.mapper.EmployeeMapper;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Platform-only company onboarding: creates the {@link Company} and its
 * first {@code ADMIN} employee together, atomically. Everything after this
 * is ordinary company-scoped administration by that admin - this class only
 * covers the bootstrap step nothing else can, since a company with zero
 * employees has nobody able to call {@code EMPLOYEE_CREATE}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyOnboardingService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryRuleService salaryRuleService;
    private final SalaryCalculationService salaryCalculationService;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public CompanyOnboardingResponse onboard(CompanyOnboardingRequest request) {
        if (companyRepository.existsByCompanyCode(request.getCompanyCode())) {
            throw new ConflictException("Company already exists with code " + request.getCompanyCode());
        }
        if (employeeRepository.existsByUserId(request.getAdminUserId())) {
            throw new ConflictException("Employee already exists with userId " + request.getAdminUserId());
        }
        if (employeeRepository.existsByEmployeeCode(request.getAdminEmployeeCode())) {
            throw new ConflictException("Employee already exists with code " + request.getAdminEmployeeCode());
        }

        Company company = companyRepository.save(Company.builder()
                .companyCode(request.getCompanyCode())
                .companyName(request.getCompanyName())
                .address(request.getAddress())
                .phone(request.getPhone())
                .email(request.getCompanyEmail())
                .status(RecordStatus.ACTIVE)
                .build());

        String temporaryPassword = generateTemporaryPassword();

        Employee admin = Employee.builder()
                .userId(request.getAdminUserId())
                .employeeCode(request.getAdminEmployeeCode())
                .employeeName(request.getAdminName())
                .company(company)
                .joiningDate(LocalDate.now())
                .status(EmployeeStatus.PERMANENT)
                .recordStatus(RecordStatus.ACTIVE)
                .role(Role.ADMIN)
                .email(request.getAdminEmail())
                .grossSalary(request.getAdminGrossSalary())
                .pfBasic(request.getAdminPfBasic())
                .medicalAllowance(BigDecimal.ZERO)
                .otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .accountEnabled(true)
                .accountLocked(false)
                .failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(admin, salaryRuleService.getActiveRuleForCompany(company));
        admin = employeeRepository.save(admin);

        log.info("company.onboard companyCode={} adminUserId={}", company.getCompanyCode(), admin.getUserId());
        // record(), not recordWithActor(): the actor is the platform principal calling this endpoint
        // (resolved from SecurityContext), not the new admin the action created.
        auditService.record("COMPANY_ONBOARD", "Company", company.getCompanyCode(), AuditOutcome.SUCCESS,
                "admin=" + admin.getUserId());
        return new CompanyOnboardingResponse(company, employeeMapper.toResponse(admin), temporaryPassword);
    }

    private String generateTemporaryPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        // Guarantees at least one digit and one uppercase letter so it always passes typical password policy.
        return "Tp7-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

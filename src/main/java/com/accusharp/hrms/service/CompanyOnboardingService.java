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
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-only company onboarding: creates the {@link Company} and its
 * first {@code ADMIN} account together, atomically. Everything after this
 * is ordinary company-scoped administration by that admin - this class only
 * covers the bootstrap step nothing else can, since a company with no
 * accounts has nobody able to call {@code EMPLOYEE_CREATE}. The admin is a
 * company account, not an employee - see {@link Employee#isCompanyAccount()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyOnboardingService {

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final PlatformUserRepository platformUserRepository;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public CompanyOnboardingResponse onboard(CompanyOnboardingRequest request) {
        if (companyRepository.existsByCompanyCode(request.getCompanyCode())) {
            throw new ConflictException("Company already exists with code " + request.getCompanyCode());
        }
        if (employeeRepository.existsByUserId(request.getAdminUserId())
                || platformUserRepository.existsByUsernameIgnoreCase(request.getAdminUserId())) {
            throw new ConflictException("Employee already exists with userId " + request.getAdminUserId());
        }

        Company company = companyRepository.save(Company.builder()
                .companyCode(request.getCompanyCode())
                .companyName(request.getCompanyName())
                .address(request.getAddress())
                .phone(request.getPhone())
                .email(request.getCompanyEmail())
                .status(RecordStatus.ACTIVE)
                .build());

        String temporaryPassword = TemporaryPasswordGenerator.generate();

        // A company account, not an employee: no code, no joining date, no pay.
        Employee admin = Employee.builder()
                .userId(request.getAdminUserId())
                .employeeName(request.getAdminName())
                .company(company)
                .status(EmployeeStatus.PERMANENT)
                .recordStatus(RecordStatus.ACTIVE)
                .role(Role.ADMIN)
                .email(request.getAdminEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .accountEnabled(true)
                .accountLocked(false)
                .failedLoginAttempts(0)
                .mustChangePassword(true)
                .build();
        admin = employeeRepository.save(admin);

        log.info("company.onboard companyCode={} adminUserId={}", company.getCompanyCode(), admin.getUserId());
        // record(), not recordWithActor(): the actor is the platform principal calling this endpoint
        // (resolved from SecurityContext), not the new admin the action created.
        auditService.record("COMPANY_ONBOARD", "Company", company.getCompanyCode(), AuditOutcome.SUCCESS,
                "admin=" + admin.getUserId());
        return new CompanyOnboardingResponse(company, employeeMapper.toResponse(admin), temporaryPassword);
    }
}

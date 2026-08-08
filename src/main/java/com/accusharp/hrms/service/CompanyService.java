package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.CompanyRequest;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    @Transactional
    public Company create(CompanyRequest request) {
        if (companyRepository.existsByCompanyCode(request.getCompanyCode())) {
            throw new ConflictException("Company already exists with code " + request.getCompanyCode());
        }
        return companyRepository.save(apply(new Company(), request));
    }

    @Transactional
    public Company update(Long id, CompanyRequest request) {
        Company company = getById(id);
        companyRepository.findByCompanyCode(request.getCompanyCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another company already uses code " + request.getCompanyCode());
                });
        RecordStatus previousStatus = company.getStatus();
        Company saved = companyRepository.save(apply(company, request));
        if (previousStatus != saved.getStatus()) {
            auditService.record("COMPANY_STATUS_CHANGE", "Company", saved.getCompanyCode(),
                    AuditOutcome.SUCCESS, previousStatus + " -> " + saved.getStatus());
        }
        return saved;
    }

    /**
     * A company-scoped caller (any Employee - COMPANY_READ is granted to
     * every role, not just admins) may only ever see their own company, not
     * the platform's entire customer list. A platform principal has no
     * company of its own ({@link TenantContext} returns empty for it) and
     * sees everything, matching {@code COMPANY_CREATE/UPDATE/DELETE} already
     * being platform-only.
     */
    @Transactional(readOnly = true)
    public Company getById(Long id) {
        Company company = companyRepository.findById(id).orElseThrow(() -> NotFoundException.of("Company", id));
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            if (!callerCompanyId.equals(company.getId())) {
                throw NotFoundException.of("Company", id);
            }
        });
        return company;
    }

    @Transactional(readOnly = true)
    public List<Company> getAll() {
        return tenantContext.currentCompanyId()
                .map(companyId -> companyRepository.findById(companyId).map(List::of).orElseGet(List::of))
                .orElseGet(companyRepository::findAll);
    }

    @Transactional
    public void delete(Long id) {
        companyRepository.delete(getById(id));
    }

    private Company apply(Company company, CompanyRequest request) {
        company.setCompanyCode(request.getCompanyCode());
        company.setCompanyName(request.getCompanyName());
        company.setAddress(request.getAddress());
        company.setPhone(request.getPhone());
        company.setEmail(request.getEmail());
        company.setStatus(request.getStatus());
        return company;
    }
}

package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.DesignationRequest;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DesignationRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * One row per company, plus shared ({@code company = null}) rows every
 * company can read - see {@code Department}'s Javadoc for the full
 * rationale, mirrored here identically.
 */
@Service
@RequiredArgsConstructor
public class DesignationService {

    private final DesignationRepository designationRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;

    @Transactional
    public Designation create(DesignationRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        if (codeInUse(request.getDesignationCode(), companyId)) {
            throw new ConflictException("Designation already exists with code " + request.getDesignationCode());
        }
        Designation designation = apply(new Designation(), request);
        designation.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
        return designationRepository.save(designation);
    }

    @Transactional
    public Designation update(Long id, DesignationRequest request) {
        Designation designation = getById(id);
        assertWritable(designation);
        Long companyId = designation.getCompany() == null ? null : designation.getCompany().getId();
        findByCode(request.getDesignationCode(), companyId)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another designation already uses code "
                            + request.getDesignationCode());
                });
        return designationRepository.save(apply(designation, request));
    }

    @Transactional(readOnly = true)
    public Designation getById(Long id) {
        Designation designation = designationRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Designation", id));
        assertReadable(designation);
        return designation;
    }

    @Transactional(readOnly = true)
    public List<Designation> getAll() {
        return tenantContext.currentCompanyId()
                .map(designationRepository::findByCompanyIdOrCompanyIsNull)
                .orElseGet(designationRepository::findAll);
    }

    @Transactional
    public void delete(Long id) {
        Designation designation = getById(id);
        assertWritable(designation);
        designationRepository.delete(designation);
    }

    private boolean codeInUse(String code, Long companyId) {
        if (companyId == null) {
            return designationRepository.existsByDesignationCodeAndCompanyIsNull(code);
        }
        return designationRepository.existsByDesignationCodeAndCompanyId(code, companyId)
                || designationRepository.existsByDesignationCodeAndCompanyIsNull(code);
    }

    private Optional<Designation> findByCode(String code, Long companyId) {
        if (companyId == null) {
            return designationRepository.findByDesignationCodeAndCompanyIsNull(code);
        }
        return designationRepository.findByDesignationCodeAndCompanyId(code, companyId)
                .or(() -> designationRepository.findByDesignationCodeAndCompanyIsNull(code));
    }

    /** A caller scoped to one company may read their own company's rows and every shared row. */
    private void assertReadable(Designation designation) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = designation.getCompany() == null ? null : designation.getCompany().getId();
            if (targetCompanyId != null && !callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Designation", designation.getId());
            }
        });
    }

    /** Unlike read access, a shared (company-null) row is never writable by a company caller. */
    private void assertWritable(Designation designation) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = designation.getCompany() == null ? null : designation.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Designation", designation.getId());
            }
        });
    }

    private Designation apply(Designation designation, DesignationRequest request) {
        designation.setDesignationCode(request.getDesignationCode());
        designation.setDesignationName(request.getDesignationName());
        designation.setDescription(request.getDescription());
        return designation;
    }
}

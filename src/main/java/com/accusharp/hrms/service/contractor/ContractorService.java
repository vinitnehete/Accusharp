package com.accusharp.hrms.service.contractor;

import com.accusharp.hrms.dto.ContractorRequest;
import com.accusharp.hrms.dto.ContractorResponse;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.mapper.ContractorMapper;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.ContractorRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The labour contractors one company has engaged.
 *
 * <p>{@link #getEntityById} is this module's tenant-isolation choke point,
 * exactly as {@code EmployeeService#getEntityById} is for employees: every
 * other contractor-scoped service ({@code ContractorEmployeeService},
 * {@code ContractorAttendanceService}, {@code ContractorAttendanceReportService})
 * resolves its target through it before doing anything, so the cross-company
 * check lives here once. It throws 404 rather than 403 on another company's
 * id for the same reason - a 403 would confirm the contractor exists.
 *
 * <p>Unlike {@code DepartmentService} there is no shared ({@code company =
 * null}) catalog to read or refuse to write: a contractor always belongs to
 * exactly one company (see {@link Contractor}'s Javadoc), so readable and
 * writable are the same test.
 */
@Service
@RequiredArgsConstructor
public class ContractorService {

    private final ContractorRepository contractorRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final ContractorMapper contractorMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    @Transactional
    public ContractorResponse create(ContractorRequest request) {
        Long companyId = requireCompany();
        if (contractorRepository.existsByCompanyIdAndContractorCode(companyId, request.getContractorCode())) {
            throw new ConflictException("A contractor already exists with code " + request.getContractorCode());
        }
        validateAgreementWindow(request);

        Contractor contractor = apply(new Contractor(), request);
        contractor.setCompany(companyRepository.getReferenceById(companyId));
        contractor.setRecordStatus(RecordStatus.ACTIVE);
        Contractor saved = contractorRepository.save(contractor);

        auditService.record("CONTRACTOR_CREATE", "Contractor", saved.getContractorCode(),
                AuditOutcome.SUCCESS, "name=" + saved.getContractorName());
        return toResponse(saved);
    }

    @Transactional
    public ContractorResponse update(Long id, ContractorRequest request) {
        Contractor contractor = getEntityById(id);
        validateAgreementWindow(request);
        contractorRepository
                .findByCompanyIdAndContractorCode(contractor.getCompany().getId(), request.getContractorCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another contractor already uses code "
                            + request.getContractorCode());
                });

        Contractor saved = contractorRepository.save(apply(contractor, request));
        auditService.record("CONTRACTOR_UPDATE", "Contractor", saved.getContractorCode(),
                AuditOutcome.SUCCESS, "name=" + saved.getContractorName());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ContractorResponse getById(Long id) {
        return toResponse(getEntityById(id));
    }

    /** Every contractor of the caller's company, active and inactive alike. */
    @Transactional(readOnly = true)
    public List<ContractorResponse> getAll() {
        return contractorsForCaller().stream().map(this::toResponse).toList();
    }

    /** Active contractors only - what a picker offers when onboarding a worker or running a report. */
    @Transactional(readOnly = true)
    public List<ContractorResponse> getActive() {
        return contractorsForCaller().stream()
                .filter(contractor -> contractor.getRecordStatus() == RecordStatus.ACTIVE)
                .map(this::toResponse)
                .toList();
    }

    /**
     * Deactivates rather than deletes, the same rule {@code EmployeeService}
     * follows: a past month's attendance report has to keep resolving the
     * contractor it was sent to, and the workers underneath it keep their
     * generated attendance either way.
     *
     * <p>Refused while workers are still active under it. Deactivating a
     * contractor out from under a deployed workforce would leave those people
     * rostered and generating attendance with no one to send it to - which is
     * a silent failure, not a tidy-up. Deactivate the workers first.
     */
    @Transactional
    public ContractorResponse deactivate(Long id) {
        Contractor contractor = getEntityById(id);
        long active = employeeRepository.countByContractorIdAndRecordStatus(id, RecordStatus.ACTIVE);
        if (active > 0) {
            throw new ConflictException("This contractor still has " + active
                    + " active worker(s). Deactivate them before deactivating the contractor.");
        }
        contractor.setRecordStatus(RecordStatus.INACTIVE);
        Contractor saved = contractorRepository.save(contractor);
        auditService.record("CONTRACTOR_DEACTIVATE", "Contractor", saved.getContractorCode(),
                AuditOutcome.SUCCESS, null);
        return toResponse(saved);
    }

    @Transactional
    public ContractorResponse reactivate(Long id) {
        Contractor contractor = getEntityById(id);
        contractor.setRecordStatus(RecordStatus.ACTIVE);
        Contractor saved = contractorRepository.save(contractor);
        auditService.record("CONTRACTOR_REACTIVATE", "Contractor", saved.getContractorCode(),
                AuditOutcome.SUCCESS, null);
        return toResponse(saved);
    }

    // ---- the tenant choke point --------------------------------------------

    /**
     * Resolves a contractor and proves it is the caller's. Every other
     * contractor-scoped service goes through here - see class Javadoc.
     */
    @Transactional(readOnly = true)
    public Contractor getEntityById(Long id) {
        Contractor contractor = contractorRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Contractor", id));
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = contractor.getCompany() == null ? null : contractor.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Contractor", id);
            }
        });
        return contractor;
    }

    /** Active contractors of the caller's company, as entities - for the report services. */
    @Transactional(readOnly = true)
    public List<Contractor> getActiveEntities() {
        return contractorsForCaller().stream()
                .filter(contractor -> contractor.getRecordStatus() == RecordStatus.ACTIVE)
                .toList();
    }

    // ---- helpers -----------------------------------------------------------

    private List<Contractor> contractorsForCaller() {
        return tenantContext.currentCompanyId()
                .map(contractorRepository::findByCompanyIdOrderByContractorNameAsc)
                // No company in context (a platform principal, or a service-level
                // test with no SecurityContext) sees everything - the same
                // deliberate no-op TenantContext applies everywhere else.
                .orElseGet(contractorRepository::findAll);
    }

    private ContractorResponse toResponse(Contractor contractor) {
        return contractorMapper.toResponse(contractor,
                employeeRepository.countByContractorIdAndRecordStatus(contractor.getId(), RecordStatus.ACTIVE));
    }

    /**
     * A contractor is a relationship between two companies, so unlike a
     * department there is nowhere sensible to file one when the caller has no
     * company of its own - a platform principal creating a contractor would
     * be creating it for nobody.
     */
    private Long requireCompany() {
        return tenantContext.currentCompanyId().orElseThrow(() -> new BusinessRuleException(
                "A contractor belongs to a company - sign in as that company's HR or ADMIN to create one"));
    }

    private void validateAgreementWindow(ContractorRequest request) {
        if (request.getAgreementStartDate() != null && request.getAgreementEndDate() != null
                && request.getAgreementEndDate().isBefore(request.getAgreementStartDate())) {
            throw new BusinessRuleException("Agreement end date cannot be before the start date");
        }
    }

    private Contractor apply(Contractor contractor, ContractorRequest request) {
        contractor.setContractorCode(request.getContractorCode());
        contractor.setContractorName(request.getContractorName());
        contractor.setContactPerson(request.getContactPerson());
        contractor.setEmail(request.getEmail());
        contractor.setPhone(request.getPhone());
        contractor.setAddress(request.getAddress());
        contractor.setGstNo(request.getGstNo());
        contractor.setPanNo(request.getPanNo());
        contractor.setAgreementStartDate(request.getAgreementStartDate());
        contractor.setAgreementEndDate(request.getAgreementEndDate());
        contractor.setNotes(request.getNotes());
        return contractor;
    }
}

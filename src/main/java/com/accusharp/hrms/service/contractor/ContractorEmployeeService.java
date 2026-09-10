package com.accusharp.hrms.service.contractor;

import com.accusharp.hrms.dto.ContractorEmployeeRequest;
import com.accusharp.hrms.dto.ContractorEmployeeResponse;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.mapper.ContractorMapper;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.DesignationService;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * The workforce deployed under a company's labour contractors.
 *
 * <p>These are {@code Employee} rows carrying a {@code contractor_id} - see
 * {@link Employee#getContractor()} for why they share that table rather than
 * getting one of their own. This service is the only place they are created
 * or edited, and it holds the three rules that keep the two populations from
 * mixing:
 *
 * <ul>
 *   <li><b>No pay, no login, no privilege.</b> {@code create} writes no
 *       salary structure, no statutory identifier, no bank detail, no
 *       password hash, and pins {@code role} to {@code EMPLOYEE} - none of
 *       which {@link ContractorEmployeeRequest} can carry in the first place.
 *       {@code accountEnabled} is false: the contractor's worker is an
 *       attendance subject, not a user of this system.</li>
 *   <li><b>The supervisor must be ours.</b> A contractor's worker is
 *       supervised by an employee of the engaging company, never by another
 *       contractor's worker - that mapping is the whole point of the feature
 *       and {@link #resolveSupervisor} is what enforces it.</li>
 *   <li><b>A row never changes population.</b> {@link #update} refuses an id
 *       that is not already a contractor worker, and
 *       {@code EmployeeService.update} cannot reach one at all (see its
 *       {@code assertNotContractorWorker}), so nothing can promote a
 *       contractor's worker onto the company payroll or demote an employee
 *       off it by editing a foreign key.</li>
 * </ul>
 *
 * <p>{@code status} is pinned to {@link EmployeeStatus#CONTRACT} rather than
 * being a request field. It is the field {@code DefaultRosterService} reads
 * to decide who gets a free GENERAL-shift roster, and a contractor's workers
 * must be rostered explicitly for the days they are actually deployed -
 * auto-rostering them would manufacture absent days, and therefore an
 * invoice dispute, for days nobody sent them in.
 */
@Service
@RequiredArgsConstructor
public class ContractorEmployeeService {

    private final EmployeeRepository employeeRepository;
    private final ContractorService contractorService;
    private final EmployeeService employeeService;
    private final DesignationService designationService;
    private final ContractorMapper contractorMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    @Transactional
    public ContractorEmployeeResponse create(Long contractorId, ContractorEmployeeRequest request) {
        Contractor contractor = requireActiveContractor(contractorId);

        if (employeeRepository.existsByUserId(request.getUserId())) {
            // Deliberately the same message an ordinary employee create gives.
            // userId is unique platform-wide because the device feed resolves a
            // punch by it alone, so the clash may well be with a row this caller
            // cannot see - saying which would leak another company's data.
            throw new ConflictException("A worker or employee already exists with userId " + request.getUserId());
        }
        Long companyId = contractor.getCompany().getId();
        if (employeeRepository.existsByEmployeeCodeAndCompanyId(request.getEmployeeCode(), companyId)) {
            throw new ConflictException("This company already uses the code " + request.getEmployeeCode());
        }

        Employee worker = new Employee();
        worker.setCompany(contractor.getCompany());
        worker.setContractor(contractor);
        apply(worker, request);

        // Pinned, never taken from the request - see class Javadoc.
        worker.setStatus(EmployeeStatus.CONTRACT);
        worker.setRole(Role.EMPLOYEE);
        worker.setOvertimeEligible(false);
        worker.setAccountEnabled(false);
        worker.setMustChangePassword(false);

        Employee saved = employeeRepository.save(worker);
        auditService.record("CONTRACTOR_EMPLOYEE_CREATE", "Employee", saved.getUserId(),
                AuditOutcome.SUCCESS, "contractor=" + contractor.getContractorCode());
        return contractorMapper.toEmployeeResponse(saved);
    }

    @Transactional
    public ContractorEmployeeResponse update(Long id, ContractorEmployeeRequest request) {
        Employee worker = getEntityById(id);
        Contractor contractor = worker.getContractor();

        employeeRepository.findByUserId(request.getUserId())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another worker or employee already uses userId "
                            + request.getUserId());
                });
        employeeRepository
                .findByEmployeeCodeAndCompanyId(request.getEmployeeCode(), contractor.getCompany().getId())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another record in this company already uses the code "
                            + request.getEmployeeCode());
                });

        apply(worker, request);
        Employee saved = employeeRepository.save(worker);
        auditService.record("CONTRACTOR_EMPLOYEE_UPDATE", "Employee", saved.getUserId(),
                AuditOutcome.SUCCESS, "contractor=" + contractor.getContractorCode());
        return contractorMapper.toEmployeeResponse(saved);
    }

    /**
     * Moves a worker from one contractor to another - the real case of a
     * person staying on site while the agency supplying them changes.
     *
     * <p>Their attendance history is untouched: it is keyed by
     * {@code userId}, so days already generated stay exactly where they are
     * and keep reading against whichever contractor the report asks for
     * <em>now</em>. That is the honest answer for a live reassignment and the
     * wrong one for backfilling a past month, which is why this is a separate,
     * audited action rather than a field on {@link #update}.
     */
    @Transactional
    public ContractorEmployeeResponse reassignContractor(Long id, Long contractorId) {
        Employee worker = getEntityById(id);
        Contractor previous = worker.getContractor();
        Contractor target = requireActiveContractor(contractorId);
        if (previous.getId().equals(target.getId())) {
            return contractorMapper.toEmployeeResponse(worker);
        }
        worker.setContractor(target);
        Employee saved = employeeRepository.save(worker);
        auditService.record("CONTRACTOR_EMPLOYEE_REASSIGN", "Employee", saved.getUserId(), AuditOutcome.SUCCESS,
                "from=" + previous.getContractorCode() + " to=" + target.getContractorCode());
        return contractorMapper.toEmployeeResponse(saved);
    }

    /**
     * Off site, not deleted - the generated attendance and every report
     * already sent to the contractor must keep resolving them. Stamps
     * {@code relievingDate} if unset, the same way
     * {@code EmployeeService#deactivate} does, so a later report knows the
     * day they stopped being expected.
     */
    @Transactional
    public ContractorEmployeeResponse deactivate(Long id) {
        Employee worker = getEntityById(id);
        worker.setRecordStatus(RecordStatus.INACTIVE);
        worker.setAccountEnabled(false);
        if (worker.getRelievingDate() == null) {
            worker.setRelievingDate(LocalDate.now());
        }
        Employee saved = employeeRepository.save(worker);
        auditService.record("CONTRACTOR_EMPLOYEE_DEACTIVATE", "Employee", saved.getUserId(),
                AuditOutcome.SUCCESS, "contractor=" + saved.getContractor().getContractorCode());
        return contractorMapper.toEmployeeResponse(saved);
    }

    @Transactional
    public ContractorEmployeeResponse reactivate(Long id) {
        Employee worker = getEntityById(id);
        requireActiveContractor(worker.getContractor().getId());
        worker.setRecordStatus(RecordStatus.ACTIVE);
        worker.setRelievingDate(null);
        Employee saved = employeeRepository.save(worker);
        auditService.record("CONTRACTOR_EMPLOYEE_REACTIVATE", "Employee", saved.getUserId(),
                AuditOutcome.SUCCESS, "contractor=" + saved.getContractor().getContractorCode());
        return contractorMapper.toEmployeeResponse(saved);
    }

    // ---- reads --------------------------------------------------------------

    /** One contractor's workforce, active and off-site alike. */
    @Transactional(readOnly = true)
    public List<ContractorEmployeeResponse> getByContractor(Long contractorId) {
        contractorService.getEntityById(contractorId);
        return contractorMapper.toEmployeeResponses(
                employeeRepository.findByContractorIdOrderByEmployeeNameAsc(contractorId));
    }

    /**
     * Every contractor's workforce across the company, or one contractor's
     * when {@code contractorId} is given - the workforce screen's list. The
     * contractor's name rides on every row, which is what makes a mixed list
     * readable (see {@link ContractorEmployeeResponse}).
     */
    @Transactional(readOnly = true)
    public List<ContractorEmployeeResponse> getAll(Long contractorId, boolean activeOnly) {
        if (contractorId != null) {
            contractorService.getEntityById(contractorId);
            return contractorMapper.toEmployeeResponses(activeOnly
                    ? employeeRepository.findByContractorIdAndRecordStatusOrderByEmployeeNameAsc(
                            contractorId, RecordStatus.ACTIVE)
                    : employeeRepository.findByContractorIdOrderByEmployeeNameAsc(contractorId));
        }
        return contractorMapper.toEmployeeResponses(allForCompany(activeOnly));
    }

    @Transactional(readOnly = true)
    public ContractorEmployeeResponse getById(Long id) {
        return contractorMapper.toEmployeeResponse(getEntityById(id));
    }

    /**
     * One contractor's active workers as entities - what attendance
     * generation and the monthly report iterate. Resolves the contractor
     * through {@link ContractorService#getEntityById} first, so the tenant
     * check happens before any employee row is read.
     */
    @Transactional(readOnly = true)
    public List<Employee> activeEntitiesOf(Long contractorId) {
        contractorService.getEntityById(contractorId);
        return employeeRepository.findByContractorIdAndRecordStatusOrderByEmployeeNameAsc(
                contractorId, RecordStatus.ACTIVE);
    }

    /**
     * Resolves a contractor worker and proves it is both the caller's and
     * actually a contractor worker.
     *
     * <p>The second half matters as much as the first: without it this
     * service's ids would address every employee in the company, and an
     * update through here would strip a real employee's salary structure by
     * writing a {@link ContractorEmployeeRequest} over it. A company employee's
     * id is reported as not found, same as another company's would be.
     */
    @Transactional(readOnly = true)
    public Employee getEntityById(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Contractor worker", id));
        if (!employee.isContractorWorker()) {
            throw NotFoundException.of("Contractor worker", id);
        }
        // Reuses the contractor choke point rather than re-checking company_id
        // here: the worker's company and its contractor's company are the same
        // row by construction (see create()), and this keeps one implementation
        // of the check.
        contractorService.getEntityById(employee.getContractor().getId());
        return employee;
    }

    // ---- helpers ------------------------------------------------------------

    private List<Employee> allForCompany(boolean activeOnly) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        if (companyId == null) {
            // No company in context - the same deliberate TenantContext no-op
            // every other service has. Filtering in Java keeps this to one
            // finder rather than a fifth repository method for a case that only
            // arises for a platform principal or a SecurityContext-less test.
            return employeeRepository.findAll().stream()
                    .filter(Employee::isContractorWorker)
                    .filter(worker -> !activeOnly || worker.getRecordStatus() == RecordStatus.ACTIVE)
                    .sorted(java.util.Comparator.comparing(Employee::getEmployeeName))
                    .toList();
        }
        return activeOnly
                ? employeeRepository
                        .findByCompanyIdAndContractorIsNotNullAndRecordStatusOrderByEmployeeNameAsc(
                                companyId, RecordStatus.ACTIVE)
                : employeeRepository.findByCompanyIdAndContractorIsNotNullOrderByEmployeeNameAsc(companyId);
    }

    private Contractor requireActiveContractor(Long contractorId) {
        Contractor contractor = contractorService.getEntityById(contractorId);
        if (contractor.getRecordStatus() != RecordStatus.ACTIVE) {
            throw new BusinessRuleException("Contractor " + contractor.getContractorName()
                    + " is inactive - reactivate it before deploying workers under it");
        }
        return contractor;
    }

    private void apply(Employee worker, ContractorEmployeeRequest request) {
        worker.setUserId(request.getUserId());
        worker.setEmployeeCode(request.getEmployeeCode());
        worker.setEmployeeName(request.getEmployeeName());
        // Resolved through DesignationService.getById so it inherits that
        // service's tenant check - a worker can never be filed under another
        // company's designation. Same choke-point inheritance EmployeeService
        // relies on (SECURITY.md Phase 6).
        worker.setDesignation(request.getDesignationId() == null ? null
                : designationService.getById(request.getDesignationId()));
        worker.setSupervisor(resolveSupervisor(request.getSupervisorUserId()));
        worker.setJoiningDate(request.getJoiningDate());
        worker.setRelievingDate(request.getRelievingDate());
        worker.setDateOfBirth(request.getDateOfBirth());
        worker.setGender(request.getGender());
        worker.setPhone(request.getPhone());
        worker.setRecordStatus(request.getRecordStatus() == null ? RecordStatus.ACTIVE : request.getRecordStatus());
    }

    /**
     * The supervisor must be an employee of the engaging company, not another
     * contractor's worker.
     *
     * <p>Resolved through {@code EmployeeService.getEntityByUserId}, which
     * already refuses another company's userId with a 404. The extra check
     * here is the one that service cannot make: a contractor's worker has no
     * login and no reporting authority, so allowing one as a supervisor would
     * create a chain of approvals nobody can act on. No cycle check is needed
     * - a contractor worker is never anybody's supervisor, so the chain is at
     * most one link deep.
     */
    private Employee resolveSupervisor(String supervisorUserId) {
        if (supervisorUserId == null || supervisorUserId.isBlank()) {
            return null;
        }
        Employee supervisor = employeeService.getEntityByUserId(supervisorUserId);
        if (supervisor.isContractorWorker()) {
            throw new BusinessRuleException(
                    "A contractor's worker is supervised by one of this company's employees, not by another "
                            + "contractor's worker - " + supervisorUserId + " is "
                            + supervisor.getContractor().getContractorName() + "'s worker");
        }
        return supervisor;
    }
}

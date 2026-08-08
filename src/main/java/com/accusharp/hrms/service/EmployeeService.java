package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.EmployeeCreationResponse;
import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.mapper.EmployeeMapper;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Employee master. Owns three rules worth calling out:
 * <ul>
 *   <li>the derived salary components are recalculated on every write, so they
 *       can never drift from the current {@link SalaryRule};</li>
 *   <li>supervisor mapping is validated to stay a tree - no employee may end up
 *       reporting to themselves through any chain;</li>
 *   <li>{@link #getEntityById} and {@link #getEntityByUserId} are the tenant
 *       isolation choke point: every other service (attendance, leave, payroll,
 *       shift scheduling) resolves an employee through one of these two methods
 *       before doing anything else, so the cross-company check lives here once
 *       rather than being repeated in each of them.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final CompanyService companyService;
    private final DepartmentService departmentService;
    private final DesignationService designationService;
    private final SalaryRuleService salaryRuleService;
    private final SalaryCalculationService salaryCalculationService;
    private final EmployeeMapper employeeMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    /**
     * Without an initial password, a newly created employee could never log
     * in at all - only {@link com.accusharp.hrms.service.CompanyOnboardingService}'s
     * first admin got one before this existed. Same one-time-return contract
     * as onboarding's {@code temporaryPassword}: never logged, relayed out
     * of band, changed via {@code POST /api/auth/change-password} on first
     * login.
     */
    @Transactional
    public EmployeeCreationResponse create(EmployeeRequest request) {
        if (employeeRepository.existsByUserId(request.getUserId())) {
            throw new ConflictException("Employee already exists with userId " + request.getUserId());
        }
        if (employeeRepository.existsByEmployeeCode(request.getEmployeeCode())) {
            throw new ConflictException("Employee already exists with code " + request.getEmployeeCode());
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        Employee employee = new Employee();
        apply(employee, request);
        employee.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        recalculate(employee);
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        auditService.record("EMPLOYEE_CREATE", "Employee", response.userId(), AuditOutcome.SUCCESS,
                "role=" + response.role());
        return new EmployeeCreationResponse(response, temporaryPassword);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = getEntityById(id);

        employeeRepository.findByUserId(request.getUserId())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another employee already uses userId " + request.getUserId());
                });
        employeeRepository.findByEmployeeCode(request.getEmployeeCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another employee already uses code " + request.getEmployeeCode());
                });

        apply(employee, request);
        recalculate(employee);
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        auditService.record("EMPLOYEE_UPDATE", "Employee", response.userId(), AuditOutcome.SUCCESS,
                "role=" + response.role());
        return response;
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getById(Long id) {
        Employee employee = getEntityById(id);
        assertSelfOrManages(employee.getUserId());
        return employeeMapper.toResponse(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getByUserId(String userId) {
        Employee employee = getEntityByUserId(userId);
        assertSelfOrManages(employee.getUserId());
        return employeeMapper.toResponse(employee);
    }

    /**
     * Company-wide, no self-service restriction - used only by {@code
     * ReportController.employeeReport()}, which (like every {@code
     * REPORT_READ} endpoint) intentionally stays company-wide for
     * SUPERVISOR/HR/ADMIN. See {@link #getVisible()} for the
     * self-service-restricted list {@code EmployeeController} actually uses.
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getAll() {
        return employeeMapper.toResponses(getAllEntities());
    }

    /**
     * The employee list a caller may browse directly - unlike {@link
     * #getAll()}, restricted per SECURITY.md's "view only my own data": an
     * EMPLOYEE sees only themselves, a SUPERVISOR sees themselves plus their
     * direct reports, ADMIN/HR see the whole company.
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getVisible() {
        return employeeMapper.toResponses(getAllEntities().stream()
                .filter(employee -> isSelfOrManages(employee.getUserId()))
                .toList());
    }

    /**
     * Every employee of the caller's own company, any {@link RecordStatus} -
     * unlike {@link #getActiveEntities()}, deactivated employees are
     * included, since payroll/report history for someone no longer active
     * still needs to resolve for their own company. Scoped the same way as
     * {@link #getActiveEntities()}: everything, when there is no company in
     * context.
     */
    @Transactional(readOnly = true)
    public List<Employee> getAllEntities() {
        return tenantContext.currentCompanyId()
                .map(employeeRepository::findByCompanyId)
                .orElseGet(employeeRepository::findAll);
    }

    /**
     * The team reporting to a supervisor - the basis of every approval flow.
     * Self-service restricted the same way as {@link #getVisible()}: only
     * that supervisor themselves, or ADMIN/HR, may ask for it - otherwise
     * any EMPLOYEE could bulk-read any team's data by naming its
     * supervisor's userId.
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getTeamOf(String supervisorUserId) {
        getEntityByUserId(supervisorUserId);
        assertSelfOrManages(supervisorUserId);
        return employeeMapper.toResponses(employeeRepository.findBySupervisorUserId(supervisorUserId));
    }

    /**
     * The employee set a caller may view in bulk-roster/planner-style
     * endpoints. Unlike {@link #getTeamOf}, the requested {@code
     * supervisorUserId} is a hint, not a hard requirement to match the
     * caller's identity - ADMIN/HR may ask for any team or omit it for the
     * whole company (unchanged from before self-service scoping existed); a
     * SUPERVISOR's request is always silently forced to their own team,
     * same pattern as {@code companyId} being forced server-side elsewhere
     * in this app; a plain EMPLOYEE always gets a planner of exactly
     * themselves, regardless of what was requested.
     */
    @Transactional(readOnly = true)
    public List<Employee> plannerScope(String requestedSupervisorUserId) {
        return tenantContext.currentPrincipal()
                .filter(principal -> principal.getType() == PrincipalType.EMPLOYEE)
                .map(principal -> switch (Role.valueOf(principal.getRole())) {
                    case ADMIN, HR -> teamOrCompany(requestedSupervisorUserId);
                    case SUPERVISOR -> teamOrCompany(principal.getUsername());
                    case EMPLOYEE -> List.of(getEntityByUserId(principal.getUsername()));
                })
                .orElseGet(() -> teamOrCompany(requestedSupervisorUserId));
    }

    private List<Employee> teamOrCompany(String supervisorUserId) {
        return supervisorUserId == null
                ? getActiveEntities()
                : getActiveEntities().stream()
                        .filter(employee -> employee.getSupervisor() != null
                                && supervisorUserId.equals(employee.getSupervisor().getUserId()))
                        .toList();
    }

    @Transactional
    public EmployeeResponse assignSupervisor(String userId, String supervisorUserId) {
        Employee employee = getEntityByUserId(userId);
        Employee supervisor = supervisorUserId == null ? null : getEntityByUserId(supervisorUserId);
        validateSupervisorChain(employee, supervisor);
        employee.setSupervisor(supervisor);
        return employeeMapper.toResponse(employeeRepository.save(employee));
    }

    /** Deactivates rather than deletes - payroll history must keep resolving. */
    @Transactional
    public EmployeeResponse deactivate(Long id) {
        Employee employee = getEntityById(id);
        employee.setRecordStatus(RecordStatus.INACTIVE);
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        auditService.record("EMPLOYEE_DEACTIVATE", "Employee", response.userId(), AuditOutcome.SUCCESS, null);
        return response;
    }

    @Transactional(readOnly = true)
    public Employee getEntityById(Long id) {
        Employee employee = employeeRepository.findById(id).orElseThrow(() -> NotFoundException.of("Employee", id));
        assertAccessible(employee);
        return employee;
    }

    @Transactional(readOnly = true)
    public Employee getEntityByUserId(String userId) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> NotFoundException.of("Employee", "userId " + userId));
        assertAccessible(employee);
        return employee;
    }

    /**
     * Tenant isolation: a caller scoped to one company must never learn that
     * an employee belonging to a <em>different</em> company exists at all -
     * not just be refused access to it. That is why this throws the same
     * {@link NotFoundException} an unknown id would, rather than a 403 -
     * a 403 would confirm the record exists somewhere, a 404 does not.
     *
     * <p>No-ops when {@link TenantContext} has no company to check against
     * (no authenticated principal, a platform principal, or an employee
     * record with no company of its own) - see {@link TenantContext}'s
     * Javadoc for why each of those is intentional rather than a gap.
     */
    private void assertAccessible(Employee employee) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Employee", "userId " + employee.getUserId());
            }
        });
    }

    /**
     * "Every active employee" - the base of every whole-company operation
     * (generate payroll for everyone, generate attendance for everyone, the
     * shift planner with no supervisor filter). Scoped to the caller's own
     * company whenever one is known, for the same reason as
     * {@link #assertAccessible}: without it, an HR/ADMIN at one company
     * could trigger payroll or attendance generation across <em>every</em>
     * company on the platform, not just their own - a write-side-effect
     * version of the same tenant leak, and a more severe one.
     */
    @Transactional(readOnly = true)
    public List<Employee> getActiveEntities() {
        return tenantContext.currentCompanyId()
                .map(companyId -> employeeRepository.findByRecordStatusAndCompanyId(RecordStatus.ACTIVE, companyId))
                .orElseGet(() -> employeeRepository.findByRecordStatus(RecordStatus.ACTIVE));
    }

    /** True when the supervisor may act on this employee's requests. */
    @Transactional(readOnly = true)
    public boolean supervises(String supervisorUserId, String userId) {
        Employee employee = getEntityByUserId(userId);
        return employee.getSupervisor() != null
                && employee.getSupervisor().getUserId().equals(supervisorUserId);
    }

    /**
     * "View only my own data" (SECURITY.md): the caller must be the target
     * themselves, a SUPERVISOR who directly supervises the target, or
     * ADMIN/HR (unrestricted within their own company - the tenant check
     * that runs upstream of every call site already covers that axis). 404,
     * not 403, on failure - same rationale as {@link #assertAccessible}: a
     * 403 would confirm the record exists, just not to this caller.
     *
     * <p>No-ops under the same three conditions {@link #assertAccessible}
     * no-ops under (no principal, a platform principal, or - moot here,
     * since a principal always has its own username - no company of its
     * own), so every existing service-level test that calls services
     * directly with no {@code SecurityContext} is unaffected.
     */
    public void assertSelfOrManages(String targetUserId) {
        tenantContext.currentPrincipal()
                .filter(principal -> principal.getType() == PrincipalType.EMPLOYEE)
                .ifPresent(principal -> {
                    if (!isVisibleTo(principal, targetUserId)) {
                        throw NotFoundException.of("Employee", "userId " + targetUserId);
                    }
                });
    }

    /** Non-throwing form of {@link #assertSelfOrManages}, for filtering a list rather than rejecting a single lookup. */
    public boolean isSelfOrManages(String targetUserId) {
        return tenantContext.currentPrincipal()
                .filter(principal -> principal.getType() == PrincipalType.EMPLOYEE)
                .map(principal -> isVisibleTo(principal, targetUserId))
                .orElse(true);
    }

    private boolean isVisibleTo(UserPrincipal principal, String targetUserId) {
        if (principal.getUsername().equals(targetUserId)) {
            return true;
        }
        Role role = Role.valueOf(principal.getRole());
        if (role == Role.ADMIN || role == Role.HR) {
            return true;
        }
        return role == Role.SUPERVISOR && supervises(principal.getUsername(), targetUserId);
    }

    private void recalculate(Employee employee) {
        // The employee's own company's rule, not the caller's - correct regardless of who is asking.
        salaryCalculationService.applyCalculatedFields(employee,
                salaryRuleService.getActiveRuleForCompany(employee.getCompany()));
    }

    private void apply(Employee employee, EmployeeRequest request) {
        employee.setUserId(request.getUserId());
        employee.setEmployeeCode(request.getEmployeeCode());
        employee.setEmployeeName(request.getEmployeeName());
        employee.setCompany(request.getCompanyId() == null ? null : companyService.getById(request.getCompanyId()));
        employee.setDepartment(request.getDepartmentId() == null ? null
                : departmentService.getById(request.getDepartmentId()));
        employee.setDesignation(request.getDesignationId() == null ? null
                : designationService.getById(request.getDesignationId()));

        Employee supervisor = request.getSupervisorUserId() == null ? null
                : getEntityByUserId(request.getSupervisorUserId());
        validateSupervisorChain(employee, supervisor);
        employee.setSupervisor(supervisor);

        employee.setJoiningDate(request.getJoiningDate());
        employee.setDateOfBirth(request.getDateOfBirth());
        employee.setStatus(request.getStatus());
        employee.setRecordStatus(request.getRecordStatus() == null ? RecordStatus.ACTIVE : request.getRecordStatus());
        employee.setRole(request.getRole() == null ? Role.EMPLOYEE : request.getRole());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setGrossSalary(request.getGrossSalary());
        employee.setPfBasic(request.getPfBasic());
        employee.setMedicalAllowance(request.getMedicalAllowance());
        employee.setOtherAllowance(request.getOtherAllowance());
        employee.setOvertimeEligible(request.isOvertimeEligible());
    }

    /** Walks up the proposed chain and rejects any cycle. */
    private void validateSupervisorChain(Employee employee, Employee supervisor) {
        if (supervisor == null) {
            return;
        }
        if (employee.getUserId() != null && employee.getUserId().equals(supervisor.getUserId())) {
            throw new BusinessRuleException("An employee cannot be their own supervisor");
        }
        Set<Long> seen = new HashSet<>();
        Employee current = supervisor;
        while (current != null) {
            if (current.getId() != null && !seen.add(current.getId())) {
                break;
            }
            if (employee.getId() != null && employee.getId().equals(current.getId())) {
                throw new BusinessRuleException("Supervisor mapping would create a reporting cycle");
            }
            current = current.getSupervisor();
        }
    }
}

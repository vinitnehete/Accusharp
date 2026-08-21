package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.EmployeeCreationResponse;
import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.dto.SalaryRevisionRequest;
import com.accusharp.hrms.dto.SalaryStructureRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.SalaryRevision;
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
import com.accusharp.hrms.repository.RefreshTokenRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.shift.DefaultRosterService;
import com.accusharp.hrms.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
    private final CategoryService categoryService;
    private final SalaryRuleService salaryRuleService;
    private final SalaryCalculationService salaryCalculationService;
    private final EmployeeMapper employeeMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DefaultRosterService defaultRosterService;
    private final SalaryRevisionRepository salaryRevisionRepository;

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
        if (employeeCodeInUse(request.getCompanyId(), request.getEmployeeCode())) {
            throw new ConflictException(
                    "Employee already exists in this company with code " + request.getEmployeeCode());
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        Employee employee = new Employee();
        apply(employee, request);
        employee.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        employee.setMustChangePassword(true);
        recalculate(employee);
        Employee saved = employeeRepository.save(employee);
        EmployeeResponse response = employeeMapper.toResponse(saved);
        auditService.record("EMPLOYEE_CREATE", "Employee", response.userId(), AuditOutcome.SUCCESS,
                "role=" + response.role());
        // Permanent employees default onto the GENERAL shift for every day - see DefaultRosterService.
        defaultRosterService.ensureForEmployee(saved);
        return new EmployeeCreationResponse(response, temporaryPassword);
    }

    /**
     * HR/ADMIN-triggered password reset - the practical stand-in for
     * self-service forgot-password (this app has no email delivery
     * infrastructure to build the real thing on). Same one-time-return
     * contract as {@link #create}: a fresh temporary password, returned
     * exactly once, never logged. Also clears any failed-login lockout and
     * revokes every existing refresh token for this principal - a reset
     * that left the account locked, or an old session still valid, would
     * not actually be a recovery path.
     */
    @Transactional
    public EmployeeCreationResponse resetPassword(Long id) {
        Employee employee = getEntityById(id);
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        employee.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        employee.setMustChangePassword(true);
        employee.setPasswordChangedAt(Instant.now());
        employee.setAccountLocked(false);
        employee.setFailedLoginAttempts(0);
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        refreshTokenRepository.revokeAllForPrincipal(PrincipalType.EMPLOYEE, employee.getUserId());
        auditService.record("EMPLOYEE_PASSWORD_RESET", "Employee", response.userId(), AuditOutcome.SUCCESS, null);
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
        findByEmployeeCodeInCompany(request.getCompanyId(), request.getEmployeeCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException(
                            "Another employee in this company already uses code " + request.getEmployeeCode());
                });

        apply(employee, request);
        recalculate(employee);
        Employee saved = employeeRepository.save(employee);
        EmployeeResponse response = employeeMapper.toResponse(saved);
        auditService.record("EMPLOYEE_UPDATE", "Employee", response.userId(), AuditOutcome.SUCCESS,
                "role=" + response.role());
        // Covers status changing to PERMANENT or a re-activation - a no-op otherwise.
        defaultRosterService.ensureForEmployee(saved);
        return response;
    }

    /** employeeCode is unique per company (or per shared-null-company scope), not globally. */
    private boolean employeeCodeInUse(Long companyId, String employeeCode) {
        return companyId == null
                ? employeeRepository.existsByEmployeeCodeAndCompanyIsNull(employeeCode)
                : employeeRepository.existsByEmployeeCodeAndCompanyId(employeeCode, companyId);
    }

    private Optional<Employee> findByEmployeeCodeInCompany(Long companyId, String employeeCode) {
        return companyId == null
                ? employeeRepository.findByEmployeeCodeAndCompanyIsNull(employeeCode)
                : employeeRepository.findByEmployeeCodeAndCompanyId(employeeCode, companyId);
    }

    /**
     * Manual override for basicDA/hra/conveyance/education - the escape
     * hatch for when a real payslip needs to differ from what {@link
     * SalaryRule}'s percentages would derive. Marks the employee overridden
     * so a later plain {@link #update} (or a company-wide rule change) never
     * silently clobbers it - see {@link #regenerateSalaryStructure} to go
     * back to rule-derived values.
     */
    @Transactional
    public EmployeeResponse updateSalaryStructure(Long id, SalaryStructureRequest request) {
        Employee employee = getEntityById(id);
        employee.setBasicDA(salaryCalculationService.scaled(request.getBasicDA()));
        employee.setHra(salaryCalculationService.scaled(request.getHra()));
        employee.setConveyanceAllowance(salaryCalculationService.scaled(request.getConveyanceAllowance()));
        employee.setEducationAllowance(salaryCalculationService.scaled(request.getEducationAllowance()));
        employee.setSalaryStructureOverridden(true);
        recalculate(employee); // overridden, so this only refreshes grossSalaryWage
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        auditService.record("EMPLOYEE_SALARY_STRUCTURE_OVERRIDE", "Employee", response.userId(),
                AuditOutcome.SUCCESS, null);
        return response;
    }

    /**
     * Clears any manual override and recomputes basicDA/hra/conveyance/
     * education from the employee's current gross salary and their
     * company's <em>current</em> {@link SalaryRule} - the fix for a rule
     * change (or an override) not being reflected until this is called.
     */
    @Transactional
    public EmployeeResponse regenerateSalaryStructure(Long id) {
        Employee employee = getEntityById(id);
        employee.setSalaryStructureOverridden(false);
        recalculate(employee);
        EmployeeResponse response = employeeMapper.toResponse(employeeRepository.save(employee));
        auditService.record("EMPLOYEE_SALARY_STRUCTURE_REGENERATE", "Employee", response.userId(),
                AuditOutcome.SUCCESS, null);
        return response;
    }

    /**
     * Same regeneration as {@link #regenerateSalaryStructure}, for every
     * active employee of the caller's company - the practical response to a
     * {@code SALARY_RULE_MANAGE} change: without this, each employee's
     * structure stays whatever it was last computed as until their record
     * is next saved one at a time. Employees already overridden are left
     * alone; a bulk rule-driven refresh silently discarding a deliberate
     * per-employee override would be a surprise, not a fix - regenerate
     * those individually via {@link #regenerateSalaryStructure} instead.
     */
    @Transactional
    public int regenerateAllSalaryStructures() {
        List<Employee> employees = getActiveEntities().stream()
                .filter(employee -> !employee.isSalaryStructureOverridden())
                .toList();
        for (Employee employee : employees) {
            recalculate(employee);
        }
        employeeRepository.saveAll(employees);
        auditService.record("EMPLOYEE_SALARY_STRUCTURE_REGENERATE_ALL", "Employee", null,
                AuditOutcome.SUCCESS, "count=" + employees.size());
        return employees.size();
    }

    /**
     * Records a salary hike/promotion/correction and applies it: updates
     * grossSalary, then re-derives basicDA/hra/conveyance/education from the
     * company's current {@link SalaryRule} - unless the employee's structure
     * is overridden, in which case {@link SalaryCalculationService#applyCalculatedFields}
     * would leave those four fields stale, so the caller must supply their
     * replacements in the same request. Writes an immutable {@link
     * SalaryRevision} row before returning, regardless of which path was
     * taken - this is the audit trail {@link SalaryRule}'s own Javadoc flags
     * as missing.
     */
    @Transactional
    public EmployeeResponse reviseSalary(Long id, SalaryRevisionRequest request, String revisedBy) {
        Employee employee = getEntityById(id);
        BigDecimal previousGross = salaryCalculationService.scaled(employee.getGrossSalary());

        if (employee.isSalaryStructureOverridden()) {
            if (request.getBasicDA() == null || request.getHra() == null
                    || request.getConveyanceAllowance() == null || request.getEducationAllowance() == null) {
                throw new BusinessRuleException(
                        "This employee's salary structure is overridden - provide basicDA, hra, "
                                + "conveyanceAllowance and educationAllowance along with the new gross salary");
            }
            employee.setBasicDA(salaryCalculationService.scaled(request.getBasicDA()));
            employee.setHra(salaryCalculationService.scaled(request.getHra()));
            employee.setConveyanceAllowance(salaryCalculationService.scaled(request.getConveyanceAllowance()));
            employee.setEducationAllowance(salaryCalculationService.scaled(request.getEducationAllowance()));
        }

        employee.setGrossSalary(request.getNewGrossSalary());
        recalculate(employee);
        Employee saved = employeeRepository.save(employee);

        BigDecimal hikePercent = previousGross.signum() == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : request.getNewGrossSalary().subtract(previousGross)
                        .divide(previousGross, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);

        salaryRevisionRepository.save(SalaryRevision.builder()
                .employeeId(employee.getUserId())
                .previousGrossSalary(previousGross)
                .newGrossSalary(salaryCalculationService.scaled(request.getNewGrossSalary()))
                .hikePercent(hikePercent)
                .effectiveDate(request.getEffectiveDate())
                .reason(request.getReason())
                .remarks(request.getRemarks())
                .revisedBy(revisedBy)
                .createdAt(Instant.now())
                .build());

        EmployeeResponse response = employeeMapper.toResponse(saved);
        auditService.record("EMPLOYEE_SALARY_REVISION", "Employee", response.userId(), AuditOutcome.SUCCESS,
                "previousGross=" + previousGross + ", newGross=" + request.getNewGrossSalary()
                        + ", hikePercent=" + hikePercent + ", reason=" + request.getReason());
        return response;
    }

    /** Every revision for this employee, newest effective date first - the audit trail. */
    @Transactional(readOnly = true)
    public List<SalaryRevision> getSalaryRevisions(Long id) {
        Employee employee = getEntityById(id);
        assertSelfOrManages(employee.getUserId());
        return salaryRevisionRepository.findByEmployeeIdOrderByEffectiveDateDescCreatedAtDesc(employee.getUserId());
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

    /**
     * Deactivates rather than deletes - payroll history must keep resolving.
     *
     * <p>Also disables the login account and stamps {@code relievingDate}
     * (if not already set) - a deactivated employee must not still be able
     * to authenticate, and payroll needs a last-working-day to bound
     * proration for the month they leave in (see {@code
     * PayrollService#employedDaysInPeriod}).
     */
    @Transactional
    public EmployeeResponse deactivate(Long id) {
        Employee employee = getEntityById(id);
        employee.setRecordStatus(RecordStatus.INACTIVE);
        employee.setAccountEnabled(false);
        if (employee.getRelievingDate() == null) {
            employee.setRelievingDate(java.time.LocalDate.now());
        }
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
        employee.setCategory(request.getCategoryId() == null ? null
                : categoryService.getById(request.getCategoryId()));

        Employee supervisor = request.getSupervisorUserId() == null ? null
                : getEntityByUserId(request.getSupervisorUserId());
        validateSupervisorChain(employee, supervisor);
        employee.setSupervisor(supervisor);

        employee.setJoiningDate(request.getJoiningDate());
        employee.setDateOfBirth(request.getDateOfBirth());
        employee.setGender(request.getGender());
        employee.setStatus(request.getStatus());
        employee.setRecordStatus(request.getRecordStatus() == null ? RecordStatus.ACTIVE : request.getRecordStatus());
        employee.setRole(request.getRole() == null ? Role.EMPLOYEE : request.getRole());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setUanNo(request.getUanNo());
        employee.setEsicIpNo(request.getEsicIpNo());
        employee.setBankAccountNo(request.getBankAccountNo());
        employee.setBankIfscNo(request.getBankIfscNo());
        employee.setGrossSalary(request.getGrossSalary());
        employee.setPfBasic(request.getPfBasic());
        employee.setMedicalAllowance(request.getMedicalAllowance());
        employee.setOtherAllowance(request.getOtherAllowance());
        employee.setOvertimeEligible(request.isOvertimeEligible());
        applyStructureOverride(employee, request);
    }

    /**
     * Optional escape hatch on create/update: give basicDA/hra/conveyance/
     * education directly - the exact values a company's existing payroll
     * system already produces - instead of letting {@link SalaryRule} derive
     * them. A no-op when none are supplied, so every existing caller that
     * only ever sends grossSalary is unaffected. Partial input is rejected -
     * a structure with three typed values and one silently rule-derived
     * would not be the fixed structure the caller intended to freeze.
     */
    private void applyStructureOverride(Employee employee, EmployeeRequest request) {
        BigDecimal basicDA = request.getBasicDA();
        BigDecimal hra = request.getHra();
        BigDecimal conveyance = request.getConveyanceAllowance();
        BigDecimal education = request.getEducationAllowance();

        int provided = 0;
        if (basicDA != null) provided++;
        if (hra != null) provided++;
        if (conveyance != null) provided++;
        if (education != null) provided++;

        if (provided == 0) {
            return;
        }
        if (provided < 4) {
            throw new BusinessRuleException(
                    "Provide basicDA, hra, conveyanceAllowance and educationAllowance together, or none of them");
        }
        employee.setBasicDA(salaryCalculationService.scaled(basicDA));
        employee.setHra(salaryCalculationService.scaled(hra));
        employee.setConveyanceAllowance(salaryCalculationService.scaled(conveyance));
        employee.setEducationAllowance(salaryCalculationService.scaled(education));
        employee.setSalaryStructureOverridden(true);
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

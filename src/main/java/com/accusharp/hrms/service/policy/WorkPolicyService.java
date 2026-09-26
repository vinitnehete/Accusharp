package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.WorkPolicyRequest;
import com.accusharp.hrms.dto.WorkPolicyResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.WorkPolicy;
import com.accusharp.hrms.enums.AttendanceTracking;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.PayrollMode;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.WorkPolicyRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Managing {@link WorkPolicy} versions: who is tracked, and who is simply paid.
 *
 * <p>Create-only, like the attendance policy engine it mirrors - a change is a
 * new version and an ending is a disabled version, so what a past payroll was
 * computed under is still on the table afterwards.
 */
@Service
@RequiredArgsConstructor
public class WorkPolicyService {

    private final WorkPolicyRepository policyRepository;
    private final WorkPolicyResolver resolver;
    private final EmployeeService employeeService;
    private final ScopeRefValidator scopeRefValidator;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<WorkPolicyResponse> list() {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        List<WorkPolicy> policies = companyId == null
                ? policyRepository.findAll().stream().filter(policy -> policy.getCompany() == null).toList()
                : policyRepository.findAllByCompanyIdOrderByScopeAscVersionDesc(companyId);
        return policies.stream().map(WorkPolicyResponse::of).toList();
    }

    /**
     * What one employee actually follows on one date - the winner of the scope
     * chain, or the defaults everybody is on until somebody writes a policy.
     */
    @Transactional(readOnly = true)
    public WorkPolicyResponse effectiveFor(String userId, LocalDate date) {
        Employee employee = employeeService.getEntityByUserId(userId);
        return resolver.policyFor(employee, date)
                .map(WorkPolicyResponse::of)
                .orElseGet(WorkPolicyService::defaults);
    }

    @Transactional
    public WorkPolicyResponse create(WorkPolicyRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        RuleScope scope = request.getScope();
        String scopeRef = scopeRefValidator.normalise(scope, request.getScopeRef());

        assertCoherent(request);
        scopeRefValidator.assertExists(scope, scopeRef);

        int nextVersion = policyRepository
                .findFirstByCompanyIdAndScopeAndScopeRefOrderByVersionDesc(companyId, scope, scopeRef)
                .map(policy -> policy.getVersion() + 1)
                .orElse(1);

        WorkPolicy policy = policyRepository.save(WorkPolicy.builder()
                .company(companyId == null ? null : companyRepository.getReferenceById(companyId))
                .scope(scope)
                .scopeRef(scopeRef)
                .version(nextVersion)
                .effectiveFrom(request.getEffectiveFrom())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .attendanceTracking(request.getAttendanceTracking())
                .payrollMode(request.getPayrollMode())
                .leaveApproval(request.getLeaveApproval())
                .createdAt(Instant.now())
                .createdBy(tenantContext.currentPrincipal().map(UserPrincipal::getUsername).orElse(null))
                .notes(request.getNotes())
                .build());

        auditService.record("WORK_POLICY_CREATE", "WorkPolicy", scope + ":" + scopeRef, AuditOutcome.SUCCESS,
                "version=" + nextVersion + " tracking=" + policy.getAttendanceTracking()
                        + " pay=" + policy.getPayrollMode() + " from=" + policy.getEffectiveFrom());
        return WorkPolicyResponse.of(policy);
    }

    /**
     * Paying from attendance nobody records would be a month of zero present
     * days - refused at the point somebody writes it, rather than discovered on
     * a payslip.
     */
    private void assertCoherent(WorkPolicyRequest request) {
        if (request.getAttendanceTracking() == AttendanceTracking.NOT_TRACKED
                && request.getPayrollMode() == PayrollMode.ATTENDANCE_BASED) {
            throw new BusinessRuleException(
                    "Attendance cannot be the basis of pay for a population whose attendance is not tracked - "
                            + "choose FIXED_MONTHLY, or track their attendance");
        }
    }

    /** What an employee no policy covers follows - the behaviour this table did not change. */
    private static WorkPolicyResponse defaults() {
        return new WorkPolicyResponse(null, null, null, 0, null, true,
                AttendanceTracking.TRACKED, PayrollMode.ATTENDANCE_BASED, LeaveApprovalFlow.SUPERVISOR_THEN_HR,
                "No work policy applies: attendance is tracked, pay comes from it, and leave is endorsed by a "
                        + "supervisor and approved by HR.", null);
    }
}

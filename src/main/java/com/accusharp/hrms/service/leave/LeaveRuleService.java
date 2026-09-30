package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveRuleRequest;
import com.accusharp.hrms.entity.CreditStep;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.policy.ScopeRefValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Managing leave rules, scoped to the caller's company the same way
 * {@code EmploymentTypeService} scopes employment types: a company sees its own
 * rules plus the shared ones, and writes only its own.
 */
@Service
@RequiredArgsConstructor
public class LeaveRuleService {

    private final LeaveRuleRepository leaveRuleRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final ScopeRefValidator scopeRefValidator;

    @Transactional(readOnly = true)
    public List<LeaveRule> getAll() {
        List<LeaveRule> all = new ArrayList<>(leaveRuleRepository.findAllByCompanyIsNullOrderByLeaveTypeAscEffectiveFromDesc());
        tenantContext.currentCompanyId()
                .map(leaveRuleRepository::findAllByCompanyIdOrderByLeaveTypeAscEffectiveFromDesc)
                .ifPresent(all::addAll);
        return all;
    }

    @Transactional
    public LeaveRule create(LeaveRuleRequest request) {
        return createEach(request, Collections.singletonList(request.getScopeRef())).get(0);
    }

    /**
     * The same rule for several populations of one scope - several employees,
     * say - one rule each. All are validated before any is saved, so a wrong
     * reference saves none of them.
     */
    @Transactional
    public List<LeaveRule> createForEach(LeaveRuleRequest request) {
        if (request.getScopeRefs() == null || request.getScopeRefs().isEmpty()) {
            throw new BusinessRuleException("scopeRefs must name at least one " + request.getScope());
        }
        return createEach(request, request.getScopeRefs().stream().distinct().toList());
    }

    private List<LeaveRule> createEach(LeaveRuleRequest request, List<String> scopeRefs) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        List<LeaveRule> rules = scopeRefs.stream().map(scopeRef -> {
            LeaveRule rule = new LeaveRule();
            rule.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
            apply(rule, request, scopeRef);
            assertNoDuplicate(companyId, rule);
            return rule;
        }).toList();
        return rules.stream().map(rule -> {
            LeaveRule saved = leaveRuleRepository.save(rule);
            audit("LEAVE_RULE_CREATE", saved);
            return saved;
        }).toList();
    }

    @Transactional
    public LeaveRule update(Long id, LeaveRuleRequest request) {
        LeaveRule rule = leaveRuleRepository.findById(id).orElseThrow(() -> NotFoundException.of("Leave rule", id));
        assertWritable(rule);
        Long companyId = rule.getCompany() == null ? null : rule.getCompany().getId();

        // Validated into a transient copy first. Writing the request onto the
        // managed rule before the duplicate check would let Hibernate flush it
        // ahead of that query, and the rule would then collide with itself -
        // refusing every edit that moves its month.
        LeaveRule candidate = new LeaveRule();
        apply(candidate, request, request.getScopeRef());
        if (!sameKey(rule, candidate)) {
            assertNoDuplicate(companyId, candidate);
        }
        apply(rule, request, request.getScopeRef());

        LeaveRule saved = leaveRuleRepository.save(rule);
        audit("LEAVE_RULE_UPDATE", saved);
        return saved;
    }

    /**
     * Removes a rule. Credits already posted under it stay posted - they are in
     * the ledger with their reasons - and the next payroll or year close simply
     * runs under whatever rule is left.
     */
    @Transactional
    public void delete(Long id) {
        LeaveRule rule = leaveRuleRepository.findById(id).orElseThrow(() -> NotFoundException.of("Leave rule", id));
        assertWritable(rule);
        leaveRuleRepository.delete(rule);
        audit("LEAVE_RULE_DELETE", rule);
    }

    // ---- validation --------------------------------------------------------

    private void apply(LeaveRule rule, LeaveRuleRequest request, String requestedScopeRef) {
        LeaveType type = request.getLeaveType();
        LeaveGrant grant = request.getGrantMethod();

        if (!type.isPaid()) {
            throw new BusinessRuleException(type + " is unpaid and has no balance, so it takes no rules");
        }
        if (grant == LeaveGrant.EARNED_BY_ATTENDANCE && type != LeaveType.EARNED_LEAVE) {
            throw new BusinessRuleException("Only EARNED_LEAVE can be earned from attendance - give "
                    + type + " a yearly grant, or mark it not entitled");
        }
        if (grant == LeaveGrant.YEARLY_GRANT && request.getYearlyDays() == null) {
            throw new BusinessRuleException("A yearly grant needs yearlyDays - how many days of "
                    + type + " the year gives");
        }
        if (grant == LeaveGrant.MONTHLY_ACCRUAL && request.getMonthlyCredit() == null) {
            throw new BusinessRuleException("A monthly accrual needs monthlyCredit - how many days of "
                    + type + " each month on the books credits");
        }

        // An employment-type reference names an enum constant, so "day_wise" is
        // the same population as "DAY_WISE"; every other scope names a code
        // exactly as its master holds it.
        String requestedRef = request.getScope() == RuleScope.EMPLOYMENT_TYPE && requestedScopeRef != null
                ? requestedScopeRef.trim().toUpperCase()
                : requestedScopeRef;
        String scopeRef = scopeRefValidator.normalise(request.getScope(), requestedRef);
        scopeRefValidator.assertExists(request.getScope(), scopeRef);
        if (request.getCreditSteps() != null) {
            for (CreditStep step : request.getCreditSteps()) {
                if (step.minDays() <= 0 || step.credit() == null || step.credit().signum() < 0) {
                    throw new BusinessRuleException("Each credit step needs minDays above 0 and a credit of 0 or more");
                }
            }
        }

        rule.setScope(request.getScope());
        rule.setScopeRef(scopeRef);
        rule.setLeaveType(type);
        rule.setGrantMethod(grant);
        rule.setYearlyDays(request.getYearlyDays());
        rule.setMonthlyCredit(request.getMonthlyCredit());
        rule.setYearlyAccrualCap(request.getYearlyAccrualCap());
        rule.setFullMonthCredit(request.getFullMonthCredit());
        rule.setCreditSteps(request.getCreditSteps());
        rule.setDaysPerStatutoryDay(request.getDaysPerStatutoryDay());
        rule.setCarryForwardCap(request.getCarryForwardCap());
        rule.setExcessOverCap(request.getExcessOverCap());
        // Rules apply to whole months - and for earned leave, the month is the
        // go-live: nothing before it is ever credited.
        rule.setEffectiveFrom(request.getEffectiveFrom().withDayOfMonth(1));
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void assertNoDuplicate(Long companyId, LeaveRule rule) {
        boolean exists = companyId == null
                ? leaveRuleRepository.existsByCompanyIsNullAndScopeAndScopeRefAndLeaveTypeAndEffectiveFrom(
                        rule.getScope(), rule.getScopeRef(), rule.getLeaveType(), rule.getEffectiveFrom())
                : leaveRuleRepository.existsByCompanyIdAndScopeAndScopeRefAndLeaveTypeAndEffectiveFrom(
                        companyId, rule.getScope(), rule.getScopeRef(), rule.getLeaveType(), rule.getEffectiveFrom());
        if (exists) {
            throw new ConflictException("A " + rule.getLeaveType() + " rule for " + rule.getScopeRef()
                    + " already starts on " + rule.getEffectiveFrom() + " - edit that one instead");
        }
    }

    private void assertWritable(LeaveRule rule) {
        Long owner = rule.getCompany() == null ? null : rule.getCompany().getId();
        Long caller = tenantContext.currentCompanyId().orElse(null);
        if (!Objects.equals(owner, caller)) {
            throw NotFoundException.of("Leave rule", rule.getId());
        }
    }

    private static boolean sameKey(LeaveRule a, LeaveRule b) {
        return a.getScope() == b.getScope() && Objects.equals(a.getScopeRef(), b.getScopeRef())
                && a.getLeaveType() == b.getLeaveType()
                && Objects.equals(a.getEffectiveFrom(), b.getEffectiveFrom());
    }

    private void audit(String action, LeaveRule rule) {
        auditService.record(action, "LeaveRule", String.valueOf(rule.getId()), AuditOutcome.SUCCESS,
                rule.getLeaveType() + " " + rule.getScope() + "=" + rule.getScopeRef() + " "
                        + rule.getGrantMethod() + " from " + rule.getEffectiveFrom());
    }
}

package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.service.policy.ScopeRefs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one leave rule that applies to an employee, for a leave type, on a date.
 *
 * <p>Of the enabled rules in effect on the date - the employee's own company's
 * and the shared ones - the most specific scope wins outright, down the same
 * chain every other rule engine here uses: one employee, then their
 * designation, category, department, employment type, and the company last. A company's own rule beats a
 * shared rule of the same scope, and between versions of one rule the latest
 * effective date wins. Exactly one rule results, or none - and none means the
 * leave type behaves exactly as it did before rules existed.
 */
@Service
@RequiredArgsConstructor
public class LeaveRuleResolver {

    private final LeaveRuleRepository leaveRuleRepository;

    @Transactional(readOnly = true)
    public Optional<LeaveRule> ruleFor(Employee employee, LeaveType leaveType, LocalDate date) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        Map<RuleScope, String> refs = ScopeRefs.of(employee);

        List<LeaveRule> candidates = new ArrayList<>();
        if (companyId != null) {
            candidates.addAll(leaveRuleRepository.findAllByCompanyIdAndLeaveTypeAndEnabledTrue(companyId, leaveType));
        }
        candidates.addAll(leaveRuleRepository.findAllByCompanyIsNullAndLeaveTypeAndEnabledTrue(leaveType));

        return candidates.stream()
                .filter(rule -> !rule.getEffectiveFrom().isAfter(date))
                .filter(rule -> matches(rule, refs))
                .min(Comparator.comparingInt((LeaveRule rule) -> rule.getScope().ordinal())
                        .thenComparingInt(rule -> rule.getCompany() == null ? 1 : 0)
                        .thenComparing(LeaveRule::getEffectiveFrom, Comparator.reverseOrder()));
    }

    /**
     * A scope that names nothing - COMPANY, GLOBAL - covers everybody whatever
     * its {@code scopeRef} says, which is also what keeps rows written before
     * leave rules shared {@link RuleScope} (they hold the literal {@code "ANY"})
     * matching. Every other scope matches when it names this employee's value.
     */
    private boolean matches(LeaveRule rule, Map<RuleScope, String> refs) {
        if (!rule.getScope().requiresRef()) {
            return true;
        }
        String ref = refs.get(rule.getScope());
        return ref != null && ref.equals(rule.getScopeRef());
    }
}

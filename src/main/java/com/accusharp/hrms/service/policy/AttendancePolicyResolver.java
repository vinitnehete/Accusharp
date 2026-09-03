package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.repository.AttendancePolicyRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which attendance policy rules apply to an employee on a date.
 *
 * <p>Two questions, answered in a fixed order, and both are total:
 *
 * <ol>
 *   <li><b>Which version?</b> The one with the greatest {@code effectiveFrom}
 *       on or before the <em>attendance date</em> - never the one in force
 *       now. A rule written in September resolves nothing new for August, so
 *       replaying August produces the numbers August was already paid on. This
 *       is the whole of effective dating, and it is what makes regeneration
 *       safe to run any number of times.</li>
 *   <li><b>Which scope?</b> The most specific one that produced a version, by
 *       {@link RuleScope}'s declaration order:
 *       {@code EMPLOYEE > DESIGNATION > CATEGORY > DEPARTMENT > EMPLOYMENT_TYPE
 *       > COMPANY > GLOBAL}. Most specific wins outright - scopes are never
 *       merged, so the policy applied to an employee is always a row somebody
 *       actually wrote.</li>
 * </ol>
 *
 * <p>A winning row with {@code enabled = false} resolves to <b>nothing of that
 * type</b>, and a broader scope does not then take over. That is what "workers
 * get a ten-minute grace, managers are not tracked at all" needs: the manager's
 * disabled category rule has to beat the company rule, not fall through to it.
 *
 * <h2>One query per company, not per employee per day</h2>
 *
 * <p>{@link #policiesFor} loads a company's whole rule set once and resolves
 * every employee against it in memory. A month's generation asks this question
 * seven times per employee per day; querying per question would be tens of
 * thousands of round trips against a table holding a handful of rows. Same
 * trade {@code AttendanceService.resolveCompanyContext} already makes for the
 * attendance rule and the holiday calendar.
 */
@Service
@RequiredArgsConstructor
public class AttendancePolicyResolver {

    private final AttendancePolicyRuleRepository ruleRepository;

    /**
     * Every rule row one company could apply on or before {@code asOf},
     * fetched once. Hand the result to {@link #resolve} for each employee.
     *
     * <p>{@code asOf} should be the <b>last</b> date of the period being
     * generated: a version effective mid-month must be visible to the days
     * after it, and {@link #resolve} filters again per date.
     */
    @Transactional(readOnly = true)
    public List<AttendancePolicyRule> loadCompanyRules(Long companyId, LocalDate asOf) {
        return companyId == null
                ? ruleRepository.findApplicableGlobal(asOf)
                : ruleRepository.findApplicable(companyId, asOf);
    }

    /**
     * The policy in force for one employee on one date, from a rule set already
     * loaded by {@link #loadCompanyRules}.
     *
     * <p>Returns {@link ResolvedPolicy#NONE} when nothing matches - the state
     * every company that has configured nothing is permanently in, and the
     * short-circuit the evaluators check before doing any work at all.
     */
    public ResolvedPolicy resolve(List<AttendancePolicyRule> companyRules, Employee employee, LocalDate date) {
        if (companyRules.isEmpty()) {
            return ResolvedPolicy.NONE;
        }

        Map<RuleScope, String> refs = scopeRefsOf(employee);

        // Per type, the best (scope, version) seen so far. Two passes would be
        // clearer but this runs once per employee per day for a whole month.
        Map<RuleType, AttendancePolicyRule> winners = new EnumMap<>(RuleType.class);

        for (AttendancePolicyRule rule : companyRules) {
            if (rule.getEffectiveFrom().isAfter(date) || !matches(rule, refs)) {
                continue;
            }
            AttendancePolicyRule current = winners.get(rule.getRuleType());
            if (current == null || beats(rule, current)) {
                winners.put(rule.getRuleType(), rule);
            }
        }

        // Only now drop the disabled winners. Dropping them earlier would let a
        // broader scope win instead, which inverts the meaning of an opt-out:
        // "managers are not tracked" would silently become "managers get the
        // company rule".
        winners.values().removeIf(rule -> !rule.isEnabled());

        return winners.isEmpty() ? ResolvedPolicy.NONE : new ResolvedPolicy(Map.copyOf(winners));
    }

    /**
     * Convenience for a single employee and date - the explain endpoint and the
     * tests. Generation uses {@link #loadCompanyRules} plus {@link #resolve}
     * instead, so the rule set is fetched once for the whole run.
     */
    @Transactional(readOnly = true)
    public ResolvedPolicy policiesFor(Employee employee, LocalDate date) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        return resolve(loadCompanyRules(companyId, date), employee, date);
    }

    /**
     * Every scope that could name this employee, and what it would say. A rule
     * matches when its {@code scopeRef} equals the employee's value for that
     * scope; scopes the employee has no value for (no category assigned, say)
     * are simply absent and match nothing.
     */
    private Map<RuleScope, String> scopeRefsOf(Employee employee) {
        Map<RuleScope, String> refs = new HashMap<>();
        refs.put(RuleScope.EMPLOYEE, employee.getUserId());
        if (employee.getDesignation() != null) {
            refs.put(RuleScope.DESIGNATION, employee.getDesignation().getDesignationCode());
        }
        if (employee.getCategory() != null) {
            refs.put(RuleScope.CATEGORY, employee.getCategory().getCategoryCode());
        }
        if (employee.getDepartment() != null) {
            refs.put(RuleScope.DEPARTMENT, employee.getDepartment().getDepartmentCode());
        }
        if (employee.getStatus() != null) {
            refs.put(RuleScope.EMPLOYMENT_TYPE, employee.getStatus().name());
        }
        refs.put(RuleScope.COMPANY, RuleScope.ANY);
        refs.put(RuleScope.GLOBAL, RuleScope.ANY);
        return refs;
    }

    private boolean matches(AttendancePolicyRule rule, Map<RuleScope, String> refs) {
        String ref = refs.get(rule.getScope());
        return ref != null && ref.equals(rule.getScopeRef());
    }

    /**
     * A more specific scope always beats a less specific one, whatever their
     * dates. Within one scope the later {@code effectiveFrom} wins - and where
     * two versions somehow share a date, which {@code uk_policy_rule_effective}
     * makes impossible through the API, the higher version number breaks the
     * tie so the outcome is still deterministic rather than dependent on row
     * order.
     */
    private boolean beats(AttendancePolicyRule candidate, AttendancePolicyRule current) {
        if (candidate.getScope() != current.getScope()) {
            return candidate.getScope().ordinal() < current.getScope().ordinal();
        }
        int byDate = candidate.getEffectiveFrom().compareTo(current.getEffectiveFrom());
        return byDate != 0 ? byDate > 0 : candidate.getVersion() > current.getVersion();
    }

    /**
     * Which scopes matched at all, most specific first - for the explain
     * endpoint, which shows the rule that won <em>and the ones it beat</em>.
     * A policy that only shows its winner cannot answer "why isn't my
     * department's rule applying".
     */
    public List<AttendancePolicyRule> candidatesFor(List<AttendancePolicyRule> companyRules,
                                                    Employee employee, LocalDate date, RuleType ruleType) {
        Map<RuleScope, String> refs = scopeRefsOf(employee);
        List<AttendancePolicyRule> candidates = new ArrayList<>();
        for (AttendancePolicyRule rule : companyRules) {
            if (rule.getRuleType() == ruleType
                    && !rule.getEffectiveFrom().isAfter(date)
                    && matches(rule, refs)) {
                candidates.add(rule);
            }
        }
        candidates.sort((a, b) -> {
            int byScope = Integer.compare(a.getScope().ordinal(), b.getScope().ordinal());
            if (byScope != 0) {
                return byScope;
            }
            int byDate = b.getEffectiveFrom().compareTo(a.getEffectiveFrom());
            return byDate != 0 ? byDate : Integer.compare(b.getVersion(), a.getVersion());
        });
        return candidates;
    }
}

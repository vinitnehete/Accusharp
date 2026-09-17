package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.WorkPolicy;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.repository.WorkPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link WorkPolicy} in force for one employee on one date.
 *
 * <p>Same rules as {@link AttendancePolicyResolver}: the most specific scope
 * wins outright, a later {@code effectiveFrom} beats an earlier one within a
 * scope, and a disabled winner is dropped only after winning - dropping it
 * earlier would let a broader scope take over, which inverts the meaning of an
 * opt-out ("directors are not tracked" would quietly become "directors get the
 * company policy").
 *
 * <p>No policy at all is the state every company starts in and stays in until
 * somebody configures one: attendance tracked, pay from attendance, exactly as
 * before this existed.
 */
@Service
@RequiredArgsConstructor
public class WorkPolicyResolver {

    private final WorkPolicyRepository policyRepository;

    /** Every policy row one company could apply on or before {@code asOf}, fetched once. */
    @Transactional(readOnly = true)
    public List<WorkPolicy> loadCompanyPolicies(Long companyId, LocalDate asOf) {
        return companyId == null
                ? policyRepository.findApplicableGlobal(asOf)
                : policyRepository.findApplicable(companyId, asOf);
    }

    /** The winner from an already-loaded set - for a run that resolves many employees. */
    public Optional<WorkPolicy> resolve(List<WorkPolicy> companyPolicies, Employee employee, LocalDate date) {
        if (companyPolicies.isEmpty()) {
            return Optional.empty();
        }
        Map<RuleScope, String> refs = ScopeRefs.of(employee);

        WorkPolicy winner = null;
        for (WorkPolicy policy : companyPolicies) {
            if (policy.getEffectiveFrom().isAfter(date) || !matches(policy, refs)) {
                continue;
            }
            if (winner == null || beats(policy, winner)) {
                winner = policy;
            }
        }
        return Optional.ofNullable(winner).filter(WorkPolicy::isEnabled);
    }

    /** Convenience for one employee and date - payroll, the explain read, and the tests. */
    @Transactional(readOnly = true)
    public Optional<WorkPolicy> policyFor(Employee employee, LocalDate date) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        return resolve(loadCompanyPolicies(companyId, date), employee, date);
    }

    /** Whether this employee goes through the attendance process at all on {@code date}. */
    @Transactional(readOnly = true)
    public boolean tracksAttendance(Employee employee, LocalDate date) {
        return policyFor(employee, date).map(WorkPolicy::tracksAttendance).orElse(true);
    }

    private boolean matches(WorkPolicy policy, Map<RuleScope, String> refs) {
        String ref = refs.get(policy.getScope());
        return ref != null && ref.equals(policy.getScopeRef());
    }

    /**
     * A more specific scope always beats a less specific one, whatever their
     * dates; within one scope the later {@code effectiveFrom} wins, and the
     * higher version breaks a tie no index should allow in the first place.
     */
    private boolean beats(WorkPolicy candidate, WorkPolicy current) {
        if (candidate.getScope() != current.getScope()) {
            return candidate.getScope().ordinal() < current.getScope().ordinal();
        }
        int byDate = candidate.getEffectiveFrom().compareTo(current.getEffectiveFrom());
        return byDate != 0 ? byDate > 0 : candidate.getVersion() > current.getVersion();
    }
}

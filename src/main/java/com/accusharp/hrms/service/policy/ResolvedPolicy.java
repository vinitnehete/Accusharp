package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.RuleType;

import java.util.Map;
import java.util.Optional;

/**
 * The policy in force for one employee on one date: at most one rule per
 * {@link RuleType}, already resolved through the precedence chain.
 *
 * <p>Deliberately holds the winning {@link AttendancePolicyRule} rows rather
 * than their parsed parameters. Parsing happens at the point of use, so a rule
 * that never fires on a given day never has to be parsed at all, and the
 * trace can name the row that decided the day without a second lookup.
 *
 * <p>{@link #NONE} is the state every company that has configured nothing is
 * in, and {@link #isEmpty()} is what the evaluators check first. That
 * short-circuit is what makes "a company with no rules produces byte-identical
 * output" true by construction rather than by careful arithmetic - with no
 * rules there is no arithmetic.
 */
public record ResolvedPolicy(Map<RuleType, AttendancePolicyRule> rulesByType) {

    public static final ResolvedPolicy NONE = new ResolvedPolicy(Map.of());

    /**
     * The rule of this type that applies, if any.
     *
     * <p>Empty means one of two things, and they are the same thing to a
     * caller: nothing was configured for this employee, or the most specific
     * scope that matched was {@code enabled = false}. A disabled rule is an
     * answer - "this type does not apply to this population" - and resolution
     * has already stopped a broader scope from taking over, so callers need
     * not distinguish the cases.
     */
    public Optional<AttendancePolicyRule> rule(RuleType ruleType) {
        return Optional.ofNullable(rulesByType.get(ruleType));
    }

    public boolean isEmpty() {
        return rulesByType.isEmpty();
    }

    /** True when no rule of this evaluation scope resolved - lets a whole stage skip itself. */
    public boolean hasAnyDayRule() {
        return rulesByType.keySet().stream().anyMatch(RuleType::isDayScoped);
    }

    public boolean hasAnyMonthRule() {
        return rulesByType.keySet().stream().anyMatch(RuleType::isMonthScoped);
    }
}

package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.RuleScope;

import java.util.HashMap;
import java.util.Map;

/**
 * Every {@link RuleScope} that could name one employee, and what it would say -
 * the lookup both scope-precedence engines resolve against
 * ({@link AttendancePolicyResolver} and {@code WorkPolicyResolver}).
 *
 * <p>Shared rather than copied: a rule matches when its {@code scopeRef} equals
 * the employee's value for that scope, and two engines disagreeing about what
 * "this employee's category" means would be a silent, unexplainable difference
 * in which rule applied.
 */
public final class ScopeRefs {

    private ScopeRefs() {
    }

    /** Scopes the employee has no value for (no category assigned, say) are absent and match nothing. */
    public static Map<RuleScope, String> of(Employee employee) {
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
}

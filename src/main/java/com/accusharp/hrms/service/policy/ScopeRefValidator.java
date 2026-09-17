package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/**
 * Checks that what a rule's scope names actually exists, for every engine that
 * resolves by {@link RuleScope} - attendance policy, work policy and leave
 * rules.
 *
 * <p>A mistyped code is the failure mode worth catching: nothing rejects it at
 * write time, nothing matches it at read time, and the rule simply never
 * applies - so the company believes a policy is in force that has never once
 * been used.
 */
@Component
@RequiredArgsConstructor
public class ScopeRefValidator {

    private final EmployeeService employeeService;

    /** {@link RuleScope#ANY} for the scopes that name nothing; the trimmed value otherwise. */
    public String normalise(RuleScope scope, String scopeRef) {
        if (!scope.requiresRef()) {
            return RuleScope.ANY;
        }
        if (scopeRef == null || scopeRef.isBlank()) {
            throw new BusinessRuleException("scopeRef is required for scope " + scope);
        }
        return scopeRef.trim();
    }

    @Transactional(readOnly = true)
    public void assertExists(RuleScope scope, String scopeRef) {
        if (!scope.requiresRef()) {
            return;
        }
        if (scope == RuleScope.EMPLOYEE) {
            employeeService.getEntityByUserId(scopeRef); // 404s cross-company, same as everywhere else
            return;
        }
        if (scope == RuleScope.EMPLOYMENT_TYPE) {
            try {
                EmployeeStatus.valueOf(scopeRef);
            } catch (IllegalArgumentException ex) {
                throw new BusinessRuleException("scopeRef '" + scopeRef + "' is not an employment type - expected one of "
                        + Arrays.toString(EmployeeStatus.values()));
            }
            return;
        }
        boolean matches = employeeService.getAllEntities().stream().anyMatch(employee -> switch (scope) {
            case CATEGORY -> employee.getCategory() != null
                    && scopeRef.equals(employee.getCategory().getCategoryCode());
            case DEPARTMENT -> employee.getDepartment() != null
                    && scopeRef.equals(employee.getDepartment().getDepartmentCode());
            case DESIGNATION -> employee.getDesignation() != null
                    && scopeRef.equals(employee.getDesignation().getDesignationCode());
            default -> false;
        });
        if (!matches) {
            throw new BusinessRuleException("no employee in this company has " + scope + " '" + scopeRef
                    + "' - check the code, or the rule will silently never apply");
        }
    }
}

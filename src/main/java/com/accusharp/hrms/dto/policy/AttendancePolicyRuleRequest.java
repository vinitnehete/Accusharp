package com.accusharp.hrms.dto.policy;

import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * Appends a new version of one rule.
 *
 * <p>There is no update request, and that is deliberate: a rule is never
 * edited. Changing policy appends a version with a later {@code effectiveFrom};
 * ending one appends a version with {@code enabled = false}. The chain is the
 * history, the same way {@code SalaryRevision} is for pay.
 */
@Data
public class AttendancePolicyRuleRequest {

    @NotNull
    private RuleScope scope;

    /**
     * The employee's {@code userId}, or a category/department/designation
     * <b>code</b>, or an {@code EmployeeStatus} name. Ignored for {@code
     * COMPANY} and {@code GLOBAL}, which the service replaces with the {@code
     * '*'} sentinel.
     */
    @Size(max = 50)
    private String scopeRef;

    @NotNull
    private RuleType ruleType;

    /**
     * The day this version takes over from its predecessor. Refused when it
     * reaches back into a period payroll has already locked - see
     * {@code AttendancePolicyService}.
     *
     * <p>Note that a MONTH-scoped rule is resolved on the <b>first</b> of the
     * month, so a mid-month {@code effectiveFrom} means it governs from the
     * following month rather than partway through this one.
     */
    @NotNull
    private LocalDate effectiveFrom;

    /**
     * False means this rule type does not apply to this population - which is
     * today's behaviour for that type, not an absence of policy. Resolution
     * still picks the row and a broader scope does not take over.
     */
    private boolean enabled = true;

    /**
     * The rule's parameters, shaped by {@code ruleType}. Bound to that type's
     * record and bean-validated before anything is written, so a misspelt field
     * is a 400 here rather than a wrong salary four weeks from now.
     */
    @NotNull
    private JsonNode params;

    @Size(max = 500)
    private String notes;
}

package com.accusharp.hrms.dto.policy;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.RuleEvaluationScope;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read models for the attendance policy API. */
public final class AttendancePolicyDtos {

    private AttendancePolicyDtos() {
    }

    /**
     * One stored rule version. {@code params} is returned as the raw JSON string
     * it is stored as - it has already been validated against its type's record
     * on the way in, so a client reading it back gets exactly what it sent.
     */
    public record RuleResponse(Long id, RuleScope scope, String scopeRef, RuleType ruleType,
                               RuleEvaluationScope evaluationScope, int version,
                               LocalDate effectiveFrom, boolean enabled, String params,
                               boolean shared, String label,
                               Instant createdAt, String createdBy, String notes) {

        public static RuleResponse of(AttendancePolicyRule rule) {
            return new RuleResponse(rule.getId(), rule.getScope(), rule.getScopeRef(),
                    rule.getRuleType(), rule.getRuleType().evaluationScope(), rule.getVersion(),
                    rule.getEffectiveFrom(), rule.isEnabled(), rule.getParams(),
                    rule.getCompany() == null, rule.ruleLabel(),
                    rule.getCreatedAt(), rule.getCreatedBy(), rule.getNotes());
        }
    }

    /**
     * What actually applies to one employee on one date, per rule type.
     *
     * <p>{@code beaten} is the point of this endpoint. A policy that shows only
     * its winner cannot answer the question HR actually asks - "why isn't my
     * department's rule applying?" - so the rules that matched and lost are
     * returned alongside, most specific first.
     */
    public record EffectiveRule(RuleType ruleType, RuleEvaluationScope evaluationScope,
                                RuleResponse applied, String appliedBecause,
                                List<RuleResponse> beaten) {
    }

    /**
     * The resolved policy for one employee on one date, plus the {@code
     * AttendanceRule} thresholds it sits on top of.
     *
     * <p>The base thresholds are included deliberately. {@code AttendanceRule}
     * and the policy engine are two tables, and "why is this day a half day"
     * would otherwise have two places to look; this endpoint is the one place
     * that answers it whole.
     */
    public record EffectivePolicyResponse(String userId, LocalDate date,
                                          String categoryCode, String departmentCode,
                                          String designationCode, String employmentType,
                                          BaseThresholds base,
                                          List<EffectiveRule> rules) {
    }

    /** The company-wide {@code AttendanceRule} figures a {@code SHORT_HOURS} rule would override. */
    public record BaseThresholds(int entryWindowBufferMinutes,
                                 java.math.BigDecimal fullDayThresholdPercent,
                                 java.math.BigDecimal halfDayThresholdPercent) {
    }
}

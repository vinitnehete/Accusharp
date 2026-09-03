package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * What a month-scoped rule concluded for one employee in one month - the
 * summary-level counterpart to {@link AttendancePolicyApplication}.
 *
 * <p>Rewritten wholesale every time the summary is rebuilt: the rows for
 * {@code (userId, month)} are deleted and reinserted from a replay that starts
 * at a zero accumulator. Nothing here is ever incremented in place, which is
 * what makes the month figures reproducible no matter how many times, or in
 * what order, generation and rebuild have run.
 *
 * <p>{@code lopDays} here is the <em>penalty</em> a single rule produced, not
 * the month's total. The total lands on {@code
 * MonthlyAttendanceSummary.lopDays}, which payroll reads; {@code
 * policyLopDays} beside it carries the sum of these rows so the base figure and
 * the policy penalty stay separately legible on the slip's audit trail.
 */
@Entity
@Table(name = "attendance_policy_outcome",
        indexes = @Index(name = "idx_policy_outcome_month", columnList = "user_id, `month`"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendancePolicyOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /** ISO "yyyy-MM". Quoted because "month" is a reserved word on some databases - as on {@link MonthlyAttendanceSummary}. */
    @Column(name = "`month`", nullable = false, length = 7)
    private String month;

    /** A plain scalar, not a relation - see {@link AttendancePolicyApplication#getRuleId()}. */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private RuleType ruleType;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    @Column(name = "scope_ref", nullable = false, length = 50)
    private String scopeRef;

    /** LOP days this one rule added. Never negative - a policy rule can only ever take value away. */
    @Column(name = "lop_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal lopDays;

    /**
     * The sentence an employee gets shown - "85 min early exit across 4 days
     * against a 60 min monthly budget; budget exhausted 17 Sep; 1 later early
     * exit (24 Sep, 10 min) penalised at 0.5 day = 0.5 LOP days".
     */
    @Column(nullable = false, length = 400)
    private String explanation;
}

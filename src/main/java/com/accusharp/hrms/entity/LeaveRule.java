package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.ExcessHandling;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Who gets a leave type, and how: one rule per leave type per population, from
 * an effective month onward.
 *
 * <p>A company-wide rule, overridden by one for any narrower population -
 * "day-wise staff get no CL", "directors get none", "this one person gets five".
 * The most specific rule in effect wins outright; see {@code LeaveRuleResolver}.
 *
 * <h2>Nothing moves until a rule exists</h2>
 *
 * <p>With no rule for a leave type, a balance opens at the {@link LeaveType}
 * default it always has - CL 12, SL 8 - and earned leave is never credited. That
 * is what makes this safe to deploy: every company's leave behaves on the day
 * after exactly as it did the day before, until somebody configures it.
 *
 * <h2>Effective dating is the go-live</h2>
 *
 * <p>{@link #effectiveFrom} is always the first of a month - rules apply to whole
 * months - and for earned leave it is the go-live month: months before it are
 * never credited, so opening balances entered by hand are never double-counted.
 *
 * <p>Company null is a shared rule every company sees, the same shape
 * {@link EmploymentType} and {@link Shift} use.
 */
@Entity
@Table(name = "leave_rule",
        uniqueConstraints = @UniqueConstraint(name = "uk_leave_rule",
                columnNames = {"company_id", "scope", "scope_ref", "leave_type", "effective_from"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRule {

    /**
     * The {@code scopeRef} of a rule that names nothing. Rows written before
     * leave rules shared {@link RuleScope} hold the literal {@code "ANY"}; the
     * resolver ignores {@code scopeRef} for a scope that names nothing, so both
     * keep matching.
     */
    public static final String ANY = RuleScope.ANY;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a shared rule every company sees. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    @Column(name = "scope_ref", nullable = false, length = 50)
    private String scopeRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    /** Not {@code grant}, which is a reserved word in SQL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_method", nullable = false, length = 30)
    private LeaveGrant grantMethod;

    // ---- YEARLY_GRANT --------------------------------------------------------

    @Column(name = "yearly_days", precision = 5, scale = 1)
    private BigDecimal yearlyDays;

    // ---- EARNED_BY_ATTENDANCE - null takes EarnedLeaveCalculator's default ---

    /** What a full month with no LOP earns. Default 1.5. */
    @Column(name = "full_month_credit", precision = 4, scale = 1)
    private BigDecimal fullMonthCredit;

    /** Default {@code 20=1.0;10=0.5}. */
    @Convert(converter = CreditStepsConverter.class)
    @Column(name = "credit_steps", length = 200)
    private List<CreditStep> creditSteps;

    /**
     * Days worked per day of leave the law guarantees - 20 for an adult under
     * OSH Code 2020 s.32 (15 for an adolescent). Default 20. No month is ever
     * credited below it.
     */
    @Column(name = "days_per_statutory_day")
    private Integer daysPerStatutoryDay;

    /** What each whole month on the books credits, for {@code MONTHLY_ACCRUAL}. */
    @Column(name = "monthly_credit", precision = 4, scale = 1)
    private BigDecimal monthlyCredit;

    /**
     * The most a year may accrue in total, across every month's credit. Null is
     * no ceiling, which is what earned leave had before this column: the only
     * limit was the carry-forward cap applied at year end, long after the
     * balance had already grown.
     */
    @Column(name = "yearly_accrual_cap", precision = 5, scale = 1)
    private BigDecimal yearlyAccrualCap;

    // ---- year end ------------------------------------------------------------

    /**
     * The most that carries into the next year when the year is closed. Null
     * means nothing carries - the leave lapses, which is what every balance did
     * before this existed. 30 for EL is the OSH Code limit; anything above it is
     * reported for payout.
     */
    @Column(name = "carry_forward_cap", precision = 5, scale = 1)
    private BigDecimal carryForwardCap;

    /**
     * What happens to unused leave above {@link #carryForwardCap} when the year
     * closes. Null means {@link ExcessHandling#PAY_OUT}, which is what the close
     * has always reported. {@link ExcessHandling#LAPSE} drops it - "only 45
     * carries, the rest lapses" - but the OSH Code requires paying out EL above
     * the carry-forward limit for employees it counts as workers, so it is a
     * choice to make with the population in mind.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "excess_over_cap", length = 20)
    private ExcessHandling excessOverCap;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}

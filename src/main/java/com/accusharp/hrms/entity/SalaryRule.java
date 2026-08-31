package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Configurable payroll percentages and slabs. Changing a rule affects future
 * calculations only: employees and payroll rows persist what was computed at
 * the time.
 *
 * <p>One row per company, plus exactly one row with {@code company = null} -
 * the global default a company falls back to until it sets its own (see
 * {@link com.accusharp.hrms.service.SalaryRuleService}). Every company
 * sharing one formula was a real multi-tenancy bug, not a deliberate
 * simplification - see {@code SECURITY.md}.
 *
 * <p>Effective-dated versions are the next step if per-period audit history is
 * ever required.
 */
@Entity
// Enforces "one row per company" at the database layer, not just in
// SalaryRuleService's check-then-act - without this, two concurrent requests
// that both miss the check can each insert a row for the same company,
// leaving payroll to nondeterministically pick whichever findByCompanyId
// happens to return first. MySQL treats multiple NULLs as distinct under a
// UNIQUE index, so this doesn't also cap the shared company=null default row
// at one - that row is created once at bootstrap, not under concurrent load,
// so it wasn't the risk this closes.
@Table(name = "salary_rule", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Null means the global default every company falls back to.
     *
     * <p>{@code @JsonIgnore}: {@code SalaryRuleController} returns this
     * entity directly rather than through a DTO, and with
     * {@code spring.jpa.open-in-view=false} (the production setting - see
     * {@code application.properties}) the Hibernate session backing this
     * lazy proxy is already closed by the time Jackson serializes the
     * response, which throws rather than returning null. The caller already
     * knows which company they are asking for from their own auth context,
     * so the field adds nothing on the wire anyway.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /** basicDA = grossSalary x basicDaPercent. */
    @Column(name = "basic_da_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal basicDaPercent;

    /**
     * Government-notified minimum wage for Basic+DA. When the percentage
     * calculation above lands below this figure, the threshold wins instead -
     * HRA, conveyance and education still derive from whichever value was
     * used, so they rise with it. Zero (the default) means no floor applies.
     * This changes on its own schedule (state minimum-wage notifications),
     * independent of {@code basicDaPercent}, which is why it is its own
     * field rather than folded into the percentage.
     */
    @Column(name = "basic_da_minimum_threshold", nullable = false, precision = 15, scale = 2)
    private BigDecimal basicDaMinimumThreshold;

    /** hra, conveyance and education are percentages of basicDA. */
    @Column(name = "hra_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal hraPercent;

    @Column(name = "conveyance_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal conveyancePercent;

    @Column(name = "education_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal educationPercent;

    @Column(name = "pf_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal pfPercent;

    @Column(name = "esic_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal esicPercent;

    /** ESIC applies only up to this gross wage ceiling. */
    @Column(name = "esic_wage_ceiling", nullable = false, precision = 15, scale = 2)
    private BigDecimal esicWageCeiling;

    // Professional tax: two-step slab on gross salary.
    @Column(name = "pt_upper_threshold", nullable = false, precision = 15, scale = 2)
    private BigDecimal ptUpperThreshold;

    @Column(name = "pt_upper_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal ptUpperAmount;

    @Column(name = "pt_lower_threshold", nullable = false, precision = 15, scale = 2)
    private BigDecimal ptLowerThreshold;

    @Column(name = "pt_lower_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal ptLowerAmount;

    /** Payable days used for DAY_WISE employees. */
    @Column(name = "day_wise_days_in_month", nullable = false)
    private int dayWiseDaysInMonth;

    /** Standard paid hours per day, used for the per-hour overtime rate. */
    @Column(name = "standard_hours_per_day", nullable = false, precision = 4, scale = 1)
    private BigDecimal standardHoursPerDay;

    /** Multiplier applied to the per-hour rate for overtime. */
    @Column(name = "overtime_rate_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal overtimeRateMultiplier;

    /**
     * Flat amount deducted from the employee in the state's two Labour
     * Welfare Fund cycles (June and December) only - zero every other month.
     * A single figure per company, revised whenever the state notifies a new
     * one; see {@link com.accusharp.hrms.service.calculation.DeductionCalculationService#calculateMlwf}.
     */
    @Column(name = "mlwf_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal mlwfAmount;

    /** Sane starting values for a new rule row - the global default, or a fresh per-company one. */
    public static SalaryRule defaultRule() {
        return SalaryRule.builder()
                .basicDaPercent(new BigDecimal("50"))
                .basicDaMinimumThreshold(BigDecimal.ZERO)
                .hraPercent(new BigDecimal("40"))
                .conveyancePercent(new BigDecimal("10"))
                .educationPercent(new BigDecimal("10"))
                .pfPercent(new BigDecimal("12"))
                .esicPercent(new BigDecimal("0.75"))
                .esicWageCeiling(new BigDecimal("21000"))
                .ptUpperThreshold(new BigDecimal("10001"))
                .ptUpperAmount(new BigDecimal("200"))
                .ptLowerThreshold(new BigDecimal("7501"))
                .ptLowerAmount(new BigDecimal("175"))
                .dayWiseDaysInMonth(26)
                .standardHoursPerDay(new BigDecimal("8"))
                .overtimeRateMultiplier(new BigDecimal("1.00"))
                .mlwfAmount(BigDecimal.ZERO)
                .build();
    }
}

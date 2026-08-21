package com.accusharp.hrms.service.calculation;

import com.accusharp.hrms.entity.SalaryRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Every statutory and manual deduction. All rates and slabs come from
 * {@link SalaryRule}, so changing policy never means changing this class.
 */
@Service
@RequiredArgsConstructor
public class DeductionCalculationService {

    private static final int SCALE = SalaryCalculationService.SCALE;

    private final SalaryCalculationService salaryCalculationService;

    /** Full-month PF on the PF basic - shown on the slip, not deducted. */
    public BigDecimal calculatePf(BigDecimal pfBasic, SalaryRule rule) {
        return salaryCalculationService.percentOf(pfBasic, rule.getPfPercent());
    }

    /** PF basic prorated by payable days - the base the deduction uses. */
    public BigDecimal calculateEarnPf(BigDecimal pfBasic, BigDecimal totalDays, BigDecimal payableDays) {
        return salaryCalculationService.prorate(pfBasic, totalDays, payableDays);
    }

    public BigDecimal calculatePfDeduction(BigDecimal earnPf, SalaryRule rule) {
        return salaryCalculationService.percentOf(earnPf, rule.getPfPercent());
    }

    /**
     * ESIC applies only while the earned basicDA stays at or under the
     * ceiling; above it the employee is out of the scheme and the deduction
     * is zero. Both the ceiling test and the deduction itself are against
     * earned basicDA (this period's attendance-prorated basicDA), not the
     * full earned gross.
     */
    public BigDecimal calculateEsic(BigDecimal earnedBasicDA, SalaryRule rule) {
        if (earnedBasicDA == null || earnedBasicDA.compareTo(rule.getEsicWageCeiling()) > 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return salaryCalculationService.percentOf(earnedBasicDA, rule.getEsicPercent());
    }

    /**
     * Labour Welfare Fund: deducted from the employee only in the state's two
     * contribution cycles, June and December - zero every other month. The
     * amount itself is a single company-wide figure in {@link SalaryRule},
     * revised whenever the state notifies a new one.
     */
    public BigDecimal calculateMlwf(int month, SalaryRule rule) {
        if (month != 6 && month != 12) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return salaryCalculationService.scaled(rule.getMlwfAmount());
    }

    /** Two-step professional tax slab on the employee's gross salary. */
    public BigDecimal calculateProfessionalTax(BigDecimal grossSalary, SalaryRule rule) {
        if (grossSalary == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (grossSalary.compareTo(rule.getPtUpperThreshold()) > 0) {
            return rule.getPtUpperAmount().setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (grossSalary.compareTo(rule.getPtLowerThreshold()) >= 0) {
            return rule.getPtLowerAmount().setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Value of the unpaid days. Reported on the slip for transparency; it is
     * not added to the deduction total, because the earnings were already
     * prorated down by the same days.
     */
    public BigDecimal calculateLopDeduction(BigDecimal grossSalaryWage, BigDecimal totalDays, BigDecimal lopDays) {
        return salaryCalculationService.prorate(grossSalaryWage, totalDays, lopDays);
    }

    public BigDecimal sum(BigDecimal... amounts) {
        return Arrays.stream(amounts)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}

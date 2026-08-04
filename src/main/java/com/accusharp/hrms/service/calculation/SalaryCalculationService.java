package com.accusharp.hrms.service.calculation;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.SalaryRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives an employee's salary structure from the configured percentages.
 *
 * <p>basicDA is a percentage of gross salary; HRA, conveyance and education are
 * percentages of basicDA. Medical and other allowances are entered by hand and
 * never derived here.
 */
@Service
public class SalaryCalculationService {

    public static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Recomputes every derived field on the employee in place. */
    public void applyCalculatedFields(Employee employee, SalaryRule rule) {
        BigDecimal basicDA = percentOf(employee.getGrossSalary(), rule.getBasicDaPercent());

        employee.setBasicDA(basicDA);
        employee.setHra(percentOf(basicDA, rule.getHraPercent()));
        employee.setConveyanceAllowance(percentOf(basicDA, rule.getConveyancePercent()));
        employee.setEducationAllowance(percentOf(basicDA, rule.getEducationPercent()));

        employee.setGrossSalaryWage(basicDA
                .add(employee.getHra())
                .add(employee.getConveyanceAllowance())
                .add(employee.getEducationAllowance())
                .add(nullSafe(employee.getMedicalAllowance()))
                .add(nullSafe(employee.getOtherAllowance())));
    }

    /**
     * Prorates an earning by attendance: {@code amount x payableDays / totalDays}.
     * A zero or negative denominator yields zero rather than blowing up.
     */
    public BigDecimal prorate(BigDecimal amount, BigDecimal totalDays, BigDecimal payableDays) {
        if (amount == null || totalDays == null || totalDays.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal days = payableDays == null ? BigDecimal.ZERO : payableDays.max(BigDecimal.ZERO);
        return amount.multiply(days).divide(totalDays, SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        if (base == null || percent == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return base.multiply(percent).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (dividend == null || divisor == null || divisor.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return dividend.divide(divisor, SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

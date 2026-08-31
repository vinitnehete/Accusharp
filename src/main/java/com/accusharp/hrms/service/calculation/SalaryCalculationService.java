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

    /**
     * Recomputes every derived field on the employee in place - unless
     * {@link Employee#isSalaryStructureOverridden()}, in which case the
     * manually-set basicDA/hra/conveyance/education are left untouched and
     * only the gross-wage sum is refreshed, so it still reflects any change
     * to medical/other allowance.
     */
    public void applyCalculatedFields(Employee employee, SalaryRule rule) {
        if (!employee.isSalaryStructureOverridden()) {
            DerivedStructure structure = deriveStructure(employee.getGrossSalary(), rule);
            employee.setBasicDA(structure.basicDA());
            employee.setHra(structure.hra());
            employee.setConveyanceAllowance(structure.conveyanceAllowance());
            employee.setEducationAllowance(structure.educationAllowance());
        }

        employee.setGrossSalaryWage(nullSafe(employee.getBasicDA())
                .add(nullSafe(employee.getHra()))
                .add(nullSafe(employee.getConveyanceAllowance()))
                .add(nullSafe(employee.getEducationAllowance()))
                .add(nullSafe(employee.getMedicalAllowance()))
                .add(nullSafe(employee.getOtherAllowance())));
    }

    /** basicDA/hra/conveyance/education for a given gross salary under a given rule - the same
     *  formula {@link #applyCalculatedFields} uses, exposed so payroll can re-derive the structure
     *  that applied to a gross salary from <em>before</em> a mid-period salary revision, without
     *  needing a full historical snapshot of every derived field. */
    public DerivedStructure deriveStructure(BigDecimal grossSalary, SalaryRule rule) {
        BigDecimal basicDA = percentOf(grossSalary, rule.getBasicDaPercent());
        BigDecimal threshold = rule.getBasicDaMinimumThreshold();
        if (threshold != null && basicDA.compareTo(threshold) < 0) {
            basicDA = threshold.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return new DerivedStructure(basicDA,
                percentOf(basicDA, rule.getHraPercent()),
                percentOf(basicDA, rule.getConveyancePercent()),
                percentOf(basicDA, rule.getEducationPercent()));
    }

    public record DerivedStructure(BigDecimal basicDA, BigDecimal hra,
                                    BigDecimal conveyanceAllowance, BigDecimal educationAllowance) {
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

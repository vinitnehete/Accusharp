package com.accusharp.hrms.calculation;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.service.calculation.DeductionCalculationService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryAndDeductionCalculationTest {

    private final SalaryCalculationService salary = new SalaryCalculationService();
    private final DeductionCalculationService deductions = new DeductionCalculationService(salary);
    private final SalaryRule rule = SalaryRule.defaultRule();

    private Employee employee(BigDecimal gross) {
        return Employee.builder()
                .userId("EMP001")
                .grossSalary(gross)
                .pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250"))
                .otherAllowance(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("basic is a share of gross; the rest are shares of basic")
    void derivesSalaryStructure() {
        Employee employee = employee(new BigDecimal("20000"));

        salary.applyCalculatedFields(employee, rule);

        assertThat(employee.getBasicDA()).isEqualByComparingTo("10000.00");   // 50% of gross
        assertThat(employee.getHra()).isEqualByComparingTo("4000.00");        // 40% of basic
        assertThat(employee.getConveyanceAllowance()).isEqualByComparingTo("1000.00");
        assertThat(employee.getEducationAllowance()).isEqualByComparingTo("1000.00");
        assertThat(employee.getGrossSalaryWage()).isEqualByComparingTo("17250.00");
    }

    @Test
    @DisplayName("changing the rule changes the next calculation, not the stored one")
    void ruleChangesApplyOnRecalculation() {
        Employee employee = employee(new BigDecimal("20000"));
        salary.applyCalculatedFields(employee, rule);
        BigDecimal before = employee.getBasicDA();

        SalaryRule changed = SalaryRule.defaultRule();
        changed.setBasicDaPercent(new BigDecimal("60"));
        salary.applyCalculatedFields(employee, changed);

        assertThat(before).isEqualByComparingTo("10000.00");
        assertThat(employee.getBasicDA()).isEqualByComparingTo("12000.00");
    }

    @Test
    @DisplayName("earnings prorate by payable days")
    void proratesByPayableDays() {
        BigDecimal prorated = salary.prorate(new BigDecimal("26000"), new BigDecimal("26"),
                new BigDecimal("25"));

        assertThat(prorated).isEqualByComparingTo("25000.00");
    }

    @Test
    @DisplayName("a zero-day month prorates to zero rather than dividing by zero")
    void proratesSafelyWithNoDays() {
        assertThat(salary.prorate(new BigDecimal("26000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("professional tax follows the configured slab")
    void appliesProfessionalTaxSlab() {
        assertThat(deductions.calculateProfessionalTax(new BigDecimal("20000"), rule))
                .isEqualByComparingTo("200.00");
        assertThat(deductions.calculateProfessionalTax(new BigDecimal("8000"), rule))
                .isEqualByComparingTo("175.00");
        assertThat(deductions.calculateProfessionalTax(new BigDecimal("5000"), rule))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("ESIC stops at the wage ceiling")
    void esicStopsAboveCeiling() {
        assertThat(deductions.calculateEsic(new BigDecimal("18000"), rule))
                .isEqualByComparingTo("135.00");
        assertThat(deductions.calculateEsic(new BigDecimal("25000"), rule))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("PF is deducted on the prorated basic, not the full one")
    void pfDeductsOnProratedBasic() {
        BigDecimal earnPf = deductions.calculateEarnPf(new BigDecimal("9000"),
                new BigDecimal("26"), new BigDecimal("13"));

        assertThat(earnPf).isEqualByComparingTo("4500.00");
        assertThat(deductions.calculatePfDeduction(earnPf, rule)).isEqualByComparingTo("540.00");
    }
}

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

    @Test
    @DisplayName("basic+DA falls back to the statutory minimum when the percentage lands below it")
    void basicDaFloorsAtMinimumThreshold() {
        SalaryRule withFloor = SalaryRule.defaultRule();
        withFloor.setBasicDaMinimumThreshold(new BigDecimal("9000"));
        Employee employee = employee(new BigDecimal("15000")); // 50% -> 7500, below the 9000 floor

        salary.applyCalculatedFields(employee, withFloor);

        assertThat(employee.getBasicDA()).isEqualByComparingTo("9000.00");
        // HRA etc. derive from the floored basic, not the raw percentage result.
        assertThat(employee.getHra()).isEqualByComparingTo("3600.00");
    }

    @Test
    @DisplayName("basic+DA is untouched when the percentage already clears the threshold")
    void basicDaIgnoresThresholdWhenAlreadyAbove() {
        SalaryRule withFloor = SalaryRule.defaultRule();
        withFloor.setBasicDaMinimumThreshold(new BigDecimal("9000"));
        Employee employee = employee(new BigDecimal("20000")); // 50% -> 10000, above the floor

        salary.applyCalculatedFields(employee, withFloor);

        assertThat(employee.getBasicDA()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("a zero threshold never overrides the percentage")
    void zeroThresholdDisablesFloor() {
        Employee employee = employee(new BigDecimal("1000")); // 50% -> 500

        salary.applyCalculatedFields(employee, rule); // defaultRule() ships with threshold 0

        assertThat(employee.getBasicDA()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("MLWF deducts only in June and December")
    void mlwfDeductsOnlyInJuneAndDecember() {
        SalaryRule withMlwf = SalaryRule.defaultRule();
        withMlwf.setMlwfAmount(new BigDecimal("25"));

        assertThat(deductions.calculateMlwf(6, withMlwf)).isEqualByComparingTo("25.00");
        assertThat(deductions.calculateMlwf(12, withMlwf)).isEqualByComparingTo("25.00");
        assertThat(deductions.calculateMlwf(7, withMlwf)).isEqualByComparingTo("0.00");
        assertThat(deductions.calculateMlwf(1, rule)).isEqualByComparingTo("0.00");
    }
}

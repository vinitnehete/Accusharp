package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.CreditStep;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.service.leave.EarnedLeaveCalculator;
import com.accusharp.hrms.service.leave.EarnedLeaveCalculator.EarnedLeaveCredit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One month's earned leave, from one month's attendance. Pure - no Spring, no
 * database - because this is the number an employee will argue about, and every
 * edge of it should be provable in isolation.
 *
 * <p>The rule HR set: a full month with no LOP earns 1.5; otherwise the higher
 * of a step (20 days counted = 1, 10 = 0.5) or the legal floor of one day per
 * 20 days worked (OSH Code 2020 s.32). "Days counted" is working days minus LOP
 * - paid leave, holidays and weekly offs never reduce it.
 *
 * <p>The legal part is rounded <b>up</b> to one decimal, never to the nearest:
 * rounding to the nearest would take 1.14 down to 1.1, below what the law
 * requires, and balances are stored to one decimal.
 */
class EarnedLeaveCalculatorTest {

    private final EarnedLeaveCalculator calculator = new EarnedLeaveCalculator();

    // ---- the two examples HR gave ------------------------------------------

    @Test
    @DisplayName("a full month with no LOP earns 1.5")
    void fullMonthEarnsOneAndAHalf() {
        assertThat(credit(26, "0", true)).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("20 days counted earns 1.0")
    void twentyDaysEarnsOne() {
        assertThat(credit(26, "6", true)).isEqualByComparingTo("1.0");
    }

    // ---- between the examples: never below the law ------------------------

    @Test
    @DisplayName("24 days counted earns 1.2 - the legal floor beats the 1.0 step")
    void twentyFourDaysEarnsTheLegalFloor() {
        // A flat "1.0 for 20-25 days" would give less than one day per 20
        // worked, which the law does not allow.
        assertThat(credit(26, "2", true)).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("the legal part rounds up, so 23 days (1.15) earns 1.2, never 1.1")
    void legalFloorRoundsUp() {
        assertThat(credit(26, "3", true)).isEqualByComparingTo("1.2");
        // 15 / 20 = 0.75 -> 0.8
        assertThat(credit(26, "11", true)).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("the lower steps and the bottom of the range")
    void lowerRange() {
        assertThat(credit(26, "16", true)).isEqualByComparingTo("0.5");   // 10 days: step 0.5 = floor 0.5
        assertThat(credit(26, "18", true)).isEqualByComparingTo("0.4");   // 8 days: no step, floor 0.4
        assertThat(credit(26, "26", true)).isEqualByComparingTo("0.0");   // nothing counted
    }

    @Test
    @DisplayName("half a day of LOP is still LOP - no full-month credit")
    void halfDayLopBreaksTheFullMonth() {
        // 25.5 / 20 = 1.275 -> 1.3
        assertThat(credit(26, "0.5", true)).isEqualByComparingTo("1.3");
    }

    @Test
    @DisplayName("someone employed for part of the month gets no full-month credit, even with no LOP")
    void partMonthIsNotAFullMonth() {
        // Joined on the 15th: 13 working days, no LOP. A full 1.5 would pay a
        // half month as a whole one. 13 / 20 = 0.65 -> 0.7.
        assertThat(credit(13, "0", false)).isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("a month with no weekly off earns the legal 1.55 (1.6), not the 1.5 cap")
    void fullMonthNeverBelowTheLaw() {
        // A day-wise worker with no weekly off: 31 working days / 20 = 1.55.
        // The full-month figure is a floor HR offers, not a ceiling on the law.
        assertThat(credit(31, "0", true)).isEqualByComparingTo("1.6");
    }

    @Test
    @DisplayName("across every possible month, the credit is never below one day per 20 counted")
    void neverBelowTheLaw() {
        for (int lop = 0; lop <= 31; lop++) {
            BigDecimal counted = BigDecimal.valueOf(31 - lop);
            BigDecimal legal = counted.divide(BigDecimal.valueOf(20), 4, RoundingMode.HALF_UP);

            assertThat(credit(31, String.valueOf(lop), true))
                    .as("31 working days, %d LOP", lop)
                    .isGreaterThanOrEqualTo(legal);
        }
    }

    // ---- explanation and configuration -------------------------------------

    @Test
    @DisplayName("every credit carries the reason for it")
    void basisExplainsTheNumber() {
        EarnedLeaveCredit full = calculator.calculate(defaultRule(), 26, BigDecimal.ZERO, true);
        assertThat(full.daysCounted()).isEqualByComparingTo("26");
        assertThat(full.basis()).contains("full month");

        EarnedLeaveCredit partial = calculator.calculate(defaultRule(), 26, new BigDecimal("2"), true);
        assertThat(partial.basis()).contains("24").contains("legal floor");
    }

    @Test
    @DisplayName("a rule that leaves the numbers unset gets 1.5 / 20=1.0 / 10=0.5 / one per 20")
    void defaultsWhenUnset() {
        LeaveRule bare = LeaveRule.builder()
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE).build();

        assertThat(calculator.calculate(bare, 26, BigDecimal.ZERO, true).credit()).isEqualByComparingTo("1.5");
        assertThat(calculator.calculate(bare, 26, new BigDecimal("6"), true).credit()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a company's own numbers are honoured")
    void companyNumbersAreHonoured() {
        LeaveRule generous = LeaveRule.builder()
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE)
                .fullMonthCredit(new BigDecimal("2.0"))
                .creditSteps(List.of(new CreditStep(22, new BigDecimal("1.5"))))
                .daysPerStatutoryDay(20)
                .build();

        assertThat(calculator.calculate(generous, 26, BigDecimal.ZERO, true).credit()).isEqualByComparingTo("2.0");
        // 22 counted: step 1.5 beats floor 1.1
        assertThat(calculator.calculate(generous, 26, new BigDecimal("4"), true).credit()).isEqualByComparingTo("1.5");
    }

    // ---- helpers -----------------------------------------------------------

    private BigDecimal credit(long workingDays, String lopDays, boolean employedWholeMonth) {
        return calculator.calculate(defaultRule(), workingDays, new BigDecimal(lopDays), employedWholeMonth).credit();
    }

    private static LeaveRule defaultRule() {
        return LeaveRule.builder()
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE)
                .fullMonthCredit(new BigDecimal("1.5"))
                .creditSteps(List.of(new CreditStep(20, new BigDecimal("1.0")),
                        new CreditStep(10, new BigDecimal("0.5"))))
                .daysPerStatutoryDay(20)
                .build();
    }
}

package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.CreditStep;
import com.accusharp.hrms.entity.LeaveRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * One month's earned leave from one month's attendance. Pure: no database, no
 * clock - the number an employee will argue about has to be provable alone.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li><b>Days counted</b> = working days minus LOP. Paid leave, holidays and
 *       weekly offs never reduce it - they are not working days, or they are
 *       paid ones.</li>
 *   <li>A <b>full month</b> - employed all month, some working days, no LOP at
 *       all - earns the full-month credit, 1.5 by default.</li>
 *   <li>Otherwise the <b>highest step</b> the days counted reach: 20 = 1.0,
 *       10 = 0.5 by default.</li>
 *   <li>Either way, <b>never below the law</b>: one day per 20 worked (OSH Code
 *       2020 s.32, in force since 21 November 2025). A flat step alone would give
 *       1.0 for 24 days where the law gives 1.2, and in a month with no weekly
 *       off even 1.5 is below the law's 31 / 20 = 1.55.</li>
 * </ul>
 *
 * <p>The legal figure is rounded <b>up</b> to one decimal, never to the nearest:
 * the nearest would take 1.14 to 1.1, below what is owed. One decimal because
 * that is the precision balances are stored at.
 */
@Component
public class EarnedLeaveCalculator {

    static final BigDecimal DEFAULT_FULL_MONTH_CREDIT = new BigDecimal("1.5");
    static final List<CreditStep> DEFAULT_STEPS = List.of(
            new CreditStep(20, new BigDecimal("1.0")),
            new CreditStep(10, new BigDecimal("0.5")));
    static final int DEFAULT_DAYS_PER_STATUTORY_DAY = 20;

    /**
     * @param daysCounted working days minus LOP
     * @param credit      the days of earned leave this month earns
     * @param basis       the reason, in words - stored with the credit
     */
    public record EarnedLeaveCredit(BigDecimal daysCounted, BigDecimal credit, String basis) {
    }

    public EarnedLeaveCredit calculate(LeaveRule rule, long workingDays, BigDecimal lopDays,
                                       boolean employedWholeMonth) {
        BigDecimal lop = lopDays == null ? BigDecimal.ZERO : lopDays;
        BigDecimal counted = BigDecimal.valueOf(workingDays).subtract(lop)
                .max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
        int perDay = daysPerStatutoryDay(rule);
        BigDecimal legal = counted.divide(BigDecimal.valueOf(perDay), 1, RoundingMode.CEILING);

        boolean fullMonth = workingDays > 0 && lop.signum() == 0 && employedWholeMonth;
        if (fullMonth) {
            BigDecimal full = fullMonthCredit(rule);
            BigDecimal credit = full.max(legal).setScale(1, RoundingMode.HALF_UP);
            String basis = credit.compareTo(full) == 0
                    ? "full month, no LOP: %s".formatted(plain(full))
                    : "full month, no LOP: %s, raised to the legal floor of %s (%s days / %d)"
                            .formatted(plain(full), plain(credit), plain(counted), perDay);
            return new EarnedLeaveCredit(counted, credit, basis);
        }

        BigDecimal step = stepCredit(rule, counted);
        BigDecimal credit = step.max(legal).setScale(1, RoundingMode.HALF_UP);
        String basis = ("%s of %d working days counted (%s LOP%s): step %s, legal floor %s (%s / %d, rounded up)"
                + " - credited %s")
                .formatted(plain(counted), workingDays, plain(lop),
                        employedWholeMonth ? "" : ", not employed the whole month",
                        plain(step), plain(legal), plain(counted), perDay, plain(credit));
        return new EarnedLeaveCredit(counted, credit, basis);
    }

    private static BigDecimal stepCredit(LeaveRule rule, BigDecimal counted) {
        List<CreditStep> steps = rule.getCreditSteps() == null ? DEFAULT_STEPS : rule.getCreditSteps();
        return steps.stream()
                .sorted(Comparator.comparingInt(CreditStep::minDays).reversed())
                .filter(step -> counted.compareTo(BigDecimal.valueOf(step.minDays())) >= 0)
                .map(CreditStep::credit)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal fullMonthCredit(LeaveRule rule) {
        return rule.getFullMonthCredit() == null ? DEFAULT_FULL_MONTH_CREDIT : rule.getFullMonthCredit();
    }

    private static int daysPerStatutoryDay(LeaveRule rule) {
        Integer perDay = rule.getDaysPerStatutoryDay();
        return perDay == null || perDay <= 0 ? DEFAULT_DAYS_PER_STATUTORY_DAY : perDay;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

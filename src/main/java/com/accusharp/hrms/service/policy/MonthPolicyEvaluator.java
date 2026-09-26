package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.EarlyExitBudget;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.LateMarkAccumulation;
import com.accusharp.hrms.entity.AttendancePolicyOutcome;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.RuleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Replays the month-scoped policy rules over a month's days, in date order.
 *
 * <h2>Why these cannot be evaluated during generation</h2>
 *
 * <p>A budget consumed day by day and an Nth-occurrence counter are
 * <b>stateful and order-dependent</b>. Evaluating them while generating a day
 * would mean regenerating day 12 alone spends budget the other days have
 * already spent, so the same month would score differently depending on which
 * days happened to be touched since - and attendance is regenerated routinely,
 * to pick up punches that arrived late.
 *
 * <p>So they are not evaluated during generation at all. They are a <b>fold</b>
 * over the month's stored days: accumulator starts at zero, days are visited in
 * {@code attendanceDate} order, and the result is written wholesale over
 * whatever was there before. Nothing is ever incremented in place, and nothing
 * is written back onto a day. Run it a hundred times and the hundredth answer
 * is the first answer.
 *
 * <p>That is also why manual and locked days can safely be counted here while
 * being untouchable by the day-scoped rules: the only output is a
 * summary-level figure, so there is no way for this class to overwrite a human
 * correction even in principle. A day HR corrected to a half day with an
 * eighteen-minute early exit is still an eighteen-minute early exit, and a
 * budget that ignored it would be measuring the wrong thing.
 */
@Service
@RequiredArgsConstructor
public class MonthPolicyEvaluator {

    private final AttendancePolicyParamsCodec codec;

    /**
     * @param lopDays  the month's total policy penalty, summed over every rule
     * @param outcomes one row per rule that produced a penalty, for the trace
     */
    public record MonthPolicyResult(BigDecimal lopDays, List<AttendancePolicyOutcome> outcomes) {

        public static final MonthPolicyResult NONE =
                new MonthPolicyResult(BigDecimal.ZERO, List.of());
    }

    /**
     * @param days            the month's days; sorted here rather than trusted,
     *                        because order is the whole of the budget rule
     * @param workingDates    days the employee was expected to work. An early
     *                        exit on a weekly off consumes no budget: nobody was
     *                        expected to stay.
     * @param latePenalisedDates days a {@code LATE_ARRIVAL} rule already
     *                        downgraded. Excluded from the late-mark count - see
     *                        {@link #lateMarkPenalty}.
     */
    public MonthPolicyResult apply(String userId, YearMonth month,
                                   List<DailyAttendanceResponse> days,
                                   Set<LocalDate> workingDates,
                                   Set<LocalDate> latePenalisedDates,
                                   ResolvedPolicy policy) {
        if (policy.isEmpty() || !policy.hasAnyMonthRule()) {
            return MonthPolicyResult.NONE;
        }

        List<DailyAttendanceResponse> ordered = days.stream()
                .filter(day -> workingDates.contains(day.attendanceDate()))
                .sorted(Comparator.comparing(DailyAttendanceResponse::attendanceDate))
                .toList();

        List<AttendancePolicyOutcome> outcomes = new ArrayList<>();

        policy.rule(RuleType.EARLY_EXIT_BUDGET)
                .flatMap(rule -> earlyExitPenalty(userId, month, ordered, rule))
                .ifPresent(outcomes::add);

        policy.rule(RuleType.LATE_MARK_ACCUMULATION)
                .flatMap(rule -> lateMarkPenalty(userId, month, ordered, latePenalisedDates, rule))
                .ifPresent(outcomes::add);

        BigDecimal total = outcomes.stream()
                .map(AttendancePolicyOutcome::getLopDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);

        return new MonthPolicyResult(total, List.copyOf(outcomes));
    }

    /**
     * Spends the month's early-exit budget in date order, then penalises each
     * early exit after it is gone.
     *
     * <p>The day that <em>exhausts</em> the budget is covered by it: the test is
     * whether the budget was already spent <b>before</b> that day began. A
     * budget of sixty minutes therefore forgives an employee whose fourth early
     * exit takes them to seventy-five, and starts charging on the fifth. The
     * alternative - charging the day that crosses the line - is defensible too,
     * but this reading gives the employee the benefit and produces a boundary
     * that can be explained in one sentence.
     */
    private Optional<AttendancePolicyOutcome> earlyExitPenalty(String userId, YearMonth month,
                                                               List<DailyAttendanceResponse> days,
                                                               AttendancePolicyRule rule) {
        EarlyExitBudget params = codec.parse(rule, EarlyExitBudget.class);

        long consumed = 0;
        long totalEarlyExit = 0;
        int occurrences = 0;
        LocalDate exhaustedOn = null;
        List<LocalDate> penalised = new ArrayList<>();

        for (DailyAttendanceResponse day : days) {
            if (day.earlyExitMinutes() <= 0) {
                continue;
            }
            occurrences++;
            totalEarlyExit += day.earlyExitMinutes();

            if (consumed >= params.monthlyBudgetMinutes()) {
                penalised.add(day.attendanceDate());
            }
            consumed += day.earlyExitMinutes();
            if (exhaustedOn == null && consumed >= params.monthlyBudgetMinutes()) {
                exhaustedOn = day.attendanceDate();
            }
        }

        if (penalised.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal lop = params.penaltyDaysPerOccurrence()
                .multiply(BigDecimal.valueOf(penalised.size()))
                .setScale(1, RoundingMode.HALF_UP);

        String explanation = ("%d min of early exit across %d day(s) against a %d min monthly budget; "
                + "budget exhausted %s; %d later early exit(s) (%s) penalised at %s day each = %s LOP days, rule %s")
                .formatted(totalEarlyExit, occurrences, params.monthlyBudgetMinutes(),
                        exhaustedOn, penalised.size(), joinDates(penalised),
                        params.penaltyDaysPerOccurrence().toPlainString(), lop.toPlainString(),
                        rule.ruleLabel());

        return Optional.of(outcome(userId, month, rule, lop, explanation));
    }

    /**
     * Nth late mark in the month costs a fraction of a day.
     *
     * <p><b>A day a {@code LATE_ARRIVAL} rule already downgraded does not count
     * as a mark.</b> With both rules configured for one population, that day
     * would otherwise be charged twice for a single late arrival: once as a half
     * day on the day itself, and again as a mark toward this penalty. Nothing in
     * the two rules' parameters says they interact, so the guard lives here
     * rather than in a validation warning nobody reads - a company that
     * configures both gets the arithmetic it meant.
     */
    private Optional<AttendancePolicyOutcome> lateMarkPenalty(String userId, YearMonth month,
                                                              List<DailyAttendanceResponse> days,
                                                              Set<LocalDate> latePenalisedDates,
                                                              AttendancePolicyRule rule) {
        LateMarkAccumulation params = codec.parse(rule, LateMarkAccumulation.class);

        List<LocalDate> marks = days.stream()
                .filter(day -> day.lateMinutes() >= params.minimumLateMinutes())
                .map(DailyAttendanceResponse::attendanceDate)
                .filter(date -> !latePenalisedDates.contains(date))
                .toList();

        int penalties = marks.size() / params.occurrencesPerPenalty();
        if (penalties == 0) {
            return Optional.empty();
        }

        BigDecimal lop = params.penaltyLopDays()
                .multiply(BigDecimal.valueOf(penalties))
                .setScale(1, RoundingMode.HALF_UP);

        String alreadyCharged = latePenalisedDates.isEmpty() ? ""
                : " (%d late day(s) excluded, already charged by a LATE_ARRIVAL rule)"
                        .formatted(latePenalisedDates.size());

        String explanation = ("%d late mark(s) of at least %d min (%s)%s; %d x %d marks = %s LOP days, rule %s")
                .formatted(marks.size(), params.minimumLateMinutes(), joinDates(marks), alreadyCharged,
                        penalties, params.occurrencesPerPenalty(), lop.toPlainString(), rule.ruleLabel());

        return Optional.of(outcome(userId, month, rule, lop, explanation));
    }

    private AttendancePolicyOutcome outcome(String userId, YearMonth month, AttendancePolicyRule rule,
                                            BigDecimal lopDays, String explanation) {
        return AttendancePolicyOutcome.builder()
                .userId(userId).month(month.toString())
                .ruleId(rule.getId()).ruleType(rule.getRuleType()).ruleVersion(rule.getVersion())
                .scope(rule.getScope()).scopeRef(rule.getScopeRef())
                .lopDays(lopDays)
                .explanation(truncate(explanation))
                .build();
    }

    /** Only the dates, and only as many as fit - the column is 400 characters. */
    private String joinDates(List<LocalDate> dates) {
        List<String> shown = dates.stream().limit(8).map(LocalDate::toString).toList();
        String joined = String.join(", ", shown);
        return dates.size() > shown.size() ? joined + ", +" + (dates.size() - shown.size()) + " more" : joined;
    }

    private String truncate(String explanation) {
        return explanation.length() <= 400 ? explanation : explanation.substring(0, 397) + "...";
    }
}

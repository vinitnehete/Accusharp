package com.accusharp.hrms.policy;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.service.policy.AttendancePolicyParamsCodec;
import com.accusharp.hrms.service.policy.MonthPolicyEvaluator;
import com.accusharp.hrms.service.policy.MonthPolicyEvaluator.MonthPolicyResult;
import com.accusharp.hrms.service.policy.ResolvedPolicy;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two stateful rules, at the boundaries that decide money.
 *
 * <p>September 2026. The worked examples are sections 8 B and 8 C of
 * {@code docs/design/attendance-policy-engine.md}.
 */
class MonthPolicyEvaluatorTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final String USER = "SE10012";

    private final MonthPolicyEvaluator evaluator = new MonthPolicyEvaluator(
            new AttendancePolicyParamsCodec(Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    @DisplayName("no month rules produces no penalty and no outcome rows")
    void noRulesProducesNothing() {
        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(earlyExit(3, 20), earlyExit(9, 25)), workingDates(), Set.of(), ResolvedPolicy.NONE);

        assertThat(result).isSameAs(MonthPolicyResult.NONE);
        assertThat(result.lopDays()).isEqualByComparingTo("0");
        assertThat(result.outcomes()).isEmpty();
    }

    // ---- scenario B: the early-exit budget ---------------------------------

    @Test
    @DisplayName("scenario B: 60-minute budget spent in date order - the day that exhausts it is forgiven, the next one is charged")
    void earlyExitBudgetForgivesTheExhaustingDay() {
        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(earlyExit(3, 20), earlyExit(9, 25), earlyExit(17, 30), earlyExit(24, 10)),
                workingDates(), Set.of(), budget(60, "0.5"));

        // 3rd, 9th and 17th all begin with the budget unspent (0, 20, 45); the
        // 17th exhausts it at 75. Only the 24th is charged.
        assertThat(result.lopDays()).isEqualByComparingTo("0.5");
        assertThat(result.outcomes()).singleElement().satisfies(row -> {
            assertThat(row.getRuleType()).isEqualTo(RuleType.EARLY_EXIT_BUDGET);
            assertThat(row.getLopDays()).isEqualByComparingTo("0.5");
            assertThat(row.getExplanation())
                    .contains("85 min of early exit across 4 day(s)")
                    .contains("60 min monthly budget")
                    .contains("budget exhausted 2026-09-17")
                    .contains("2026-09-24")
                    .contains("EARLY_EXIT_BUDGET v1 scoped CATEGORY=STAFF");
        });
    }

    @Test
    @DisplayName("the boundary is consumed >= budget: landing on exactly 60 charges the next early exit, not that one")
    void earlyExitBudgetBoundaryIsExact() {
        // 20 + 40 = exactly 60 after the 9th. The 17th is then charged.
        MonthPolicyResult exact = evaluator.apply(USER, SEP,
                List.of(earlyExit(3, 20), earlyExit(9, 40), earlyExit(17, 5)),
                workingDates(), Set.of(), budget(60, "0.5"));
        assertThat(exact.lopDays()).isEqualByComparingTo("0.5");

        // One minute short of the budget after the 9th - the 17th is forgiven.
        MonthPolicyResult under = evaluator.apply(USER, SEP,
                List.of(earlyExit(3, 20), earlyExit(9, 39), earlyExit(17, 5)),
                workingDates(), Set.of(), budget(60, "0.5"));
        assertThat(under.lopDays()).isEqualByComparingTo("0");
        assertThat(under.outcomes()).isEmpty();
    }

    @Test
    @DisplayName("each early exit past the budget is charged separately - three of them cost 1.5 days")
    void earlyExitPenaltyIsPerOccurrence() {
        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(earlyExit(1, 60), earlyExit(5, 10), earlyExit(6, 10), earlyExit(7, 10)),
                workingDates(), Set.of(), budget(60, "0.5"));

        assertThat(result.lopDays()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("an early exit on a day the employee was not expected to work consumes no budget")
    void earlyExitOnANonWorkingDayIsIgnored() {
        // The 6th is a Sunday here: present in the day list, absent from workingDates.
        Set<LocalDate> working = workingDates().stream()
                .filter(date -> date.getDayOfMonth() != 6)
                .collect(Collectors.toSet());

        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(earlyExit(6, 200), earlyExit(24, 10)),
                working, Set.of(), budget(60, "0.5"));

        assertThat(result.lopDays()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the replay is order-independent: the same days shuffled give the same penalty")
    void replayIsIndependentOfInputOrder() {
        List<DailyAttendanceResponse> days = new ArrayList<>(List.of(
                earlyExit(3, 20), earlyExit(9, 25), earlyExit(17, 30), earlyExit(24, 10)));

        MonthPolicyResult inOrder = evaluator.apply(USER, SEP, days, workingDates(), Set.of(), budget(60, "0.5"));
        Collections.shuffle(days);
        MonthPolicyResult shuffled = evaluator.apply(USER, SEP, days, workingDates(), Set.of(), budget(60, "0.5"));

        assertThat(shuffled.lopDays()).isEqualByComparingTo(inOrder.lopDays());
        assertThat(shuffled.outcomes().getFirst().getExplanation())
                .isEqualTo(inOrder.outcomes().getFirst().getExplanation());
    }

    // ---- scenario C: late-mark accumulation --------------------------------

    @Test
    @DisplayName("scenario C: three late marks make a half day, and seven make one full day")
    void lateMarkAccumulationBoundaries() {
        assertThat(lateMarksPenalty(2)).isEqualByComparingTo("0");
        assertThat(lateMarksPenalty(3)).isEqualByComparingTo("0.5");
        assertThat(lateMarksPenalty(5)).isEqualByComparingTo("0.5");
        assertThat(lateMarksPenalty(6)).isEqualByComparingTo("1.0");
        assertThat(lateMarksPenalty(7)).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a day below the minimum late minutes is not a mark at all")
    void lateMarksRespectTheMinimum() {
        ResolvedPolicy policy = policy(RuleType.LATE_MARK_ACCUMULATION,
                "{\"minimumLateMinutes\":10,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(late(1, 9), late(2, 9), late(3, 9), late(4, 10), late(5, 10), late(6, 10)),
                workingDates(), Set.of(), policy);

        // Only the three 10-minute days count.
        assertThat(result.lopDays()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("the double-jeopardy guard: a day LATE_ARRIVAL already docked is not also counted as a late mark")
    void daysAlreadyPenalisedForLatenessAreNotCountedTwice() {
        ResolvedPolicy policy = policy(RuleType.LATE_MARK_ACCUMULATION,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        List<DailyAttendanceResponse> days = List.of(late(1, 5), late(2, 5), late(3, 5));

        // Without the guard these three marks would cost half a day on top of
        // whatever LATE_ARRIVAL already took off day 1.
        MonthPolicyResult guarded = evaluator.apply(USER, SEP, days, workingDates(),
                Set.of(LocalDate.of(2026, 9, 1)), policy);
        assertThat(guarded.lopDays()).isEqualByComparingTo("0");

        MonthPolicyResult unguarded = evaluator.apply(USER, SEP, days, workingDates(), Set.of(), policy);
        assertThat(unguarded.lopDays()).isEqualByComparingTo("0.5");
        assertThat(guarded.outcomes()).isEmpty();
    }

    @Test
    @DisplayName("the exclusion is named in the explanation, so the employee can see why a late day was not charged")
    void exclusionIsExplained() {
        ResolvedPolicy policy = policy(RuleType.LATE_MARK_ACCUMULATION,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        MonthPolicyResult result = evaluator.apply(USER, SEP,
                List.of(late(1, 5), late(2, 5), late(3, 5), late(4, 5)),
                workingDates(), Set.of(LocalDate.of(2026, 9, 1)), policy);

        assertThat(result.outcomes()).singleElement().satisfies(row ->
                assertThat(row.getExplanation())
                        .contains("3 late mark(s)")
                        .contains("already charged by a LATE_ARRIVAL rule"));
    }

    // ---- both together -----------------------------------------------------

    @Test
    @DisplayName("both month rules configured produce one outcome row each, and their penalties add")
    void bothRulesAccumulate() {
        ResolvedPolicy policy = new ResolvedPolicy(Map.of(
                RuleType.EARLY_EXIT_BUDGET, rule(RuleType.EARLY_EXIT_BUDGET,
                        "{\"monthlyBudgetMinutes\":60,\"penaltyDaysPerOccurrence\":0.5}"),
                RuleType.LATE_MARK_ACCUMULATION, rule(RuleType.LATE_MARK_ACCUMULATION,
                        "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}")));

        List<DailyAttendanceResponse> days = List.of(
                lateAndEarly(1, 5, 70),   // exhausts the budget
                lateAndEarly(2, 5, 10),   // charged
                lateAndEarly(3, 5, 0));

        MonthPolicyResult result = evaluator.apply(USER, SEP, days, workingDates(), Set.of(), policy);

        // 0.5 from the budget overrun + 0.5 from three late marks.
        assertThat(result.lopDays()).isEqualByComparingTo("1.0");
        assertThat(result.outcomes()).hasSize(2);
    }

    // ---- helpers -----------------------------------------------------------

    private BigDecimal lateMarksPenalty(int markCount) {
        ResolvedPolicy policy = policy(RuleType.LATE_MARK_ACCUMULATION,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");
        List<DailyAttendanceResponse> days = new ArrayList<>();
        for (int day = 1; day <= markCount; day++) {
            days.add(late(day, 5));
        }
        return evaluator.apply(USER, SEP, days, workingDates(), Set.of(), policy).lopDays();
    }

    private Set<LocalDate> workingDates() {
        Set<LocalDate> dates = new java.util.HashSet<>();
        for (int day = 1; day <= 30; day++) {
            dates.add(SEP.atDay(day));
        }
        return dates;
    }

    private DailyAttendanceResponse earlyExit(int day, int earlyExitMinutes) {
        return day(day, 0, earlyExitMinutes);
    }

    private DailyAttendanceResponse late(int day, int lateMinutes) {
        return day(day, lateMinutes, 0);
    }

    private DailyAttendanceResponse lateAndEarly(int day, int lateMinutes, int earlyExitMinutes) {
        return day(day, lateMinutes, earlyExitMinutes);
    }

    private DailyAttendanceResponse day(int day, int lateMinutes, int earlyExitMinutes) {
        return new DailyAttendanceResponse(USER, SEP.atDay(day), "GENERAL", null, null,
                new BigDecimal("8.00"), new BigDecimal("1.00"), BigDecimal.ZERO,
                lateMinutes, earlyExitMinutes, false, AttendanceStatus.PRESENT);
    }

    private ResolvedPolicy budget(int minutes, String penalty) {
        return policy(RuleType.EARLY_EXIT_BUDGET,
                "{\"monthlyBudgetMinutes\":%d,\"penaltyDaysPerOccurrence\":%s}".formatted(minutes, penalty));
    }

    private ResolvedPolicy policy(RuleType ruleType, String params) {
        return new ResolvedPolicy(Map.of(ruleType, rule(ruleType, params)));
    }

    private AttendancePolicyRule rule(RuleType ruleType, String params) {
        return AttendancePolicyRule.builder()
                .id(1L).scope(RuleScope.CATEGORY).scopeRef("STAFF")
                .ruleType(ruleType).version(1)
                .effectiveFrom(LocalDate.of(2026, 1, 1)).enabled(true).params(params)
                .build();
    }
}

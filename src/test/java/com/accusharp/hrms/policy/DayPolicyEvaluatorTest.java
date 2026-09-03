package com.accusharp.hrms.policy;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.service.policy.AttendancePolicyParamsCodec;
import com.accusharp.hrms.service.policy.DayPolicyEvaluator;
import com.accusharp.hrms.service.policy.DayPolicyEvaluator.DayContext;
import com.accusharp.hrms.service.policy.DayPolicyEvaluator.DayPolicyResult;
import com.accusharp.hrms.service.policy.ResolvedPolicy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every day-scoped rule at its boundary, because the boundary is where an
 * attendance policy is actually argued about - "I was fifteen minutes late, not
 * sixteen" is the dispute this engine has to be able to answer precisely.
 *
 * <p>Shift throughout: GENERAL 09:00-18:00, 8 paid hours, 60 min break, so 480
 * paid minutes. Matches the worked examples in section 8 of
 * {@code docs/design/attendance-policy-engine.md}.
 */
class DayPolicyEvaluatorTest {

    private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private final DayPolicyEvaluator evaluator =
            new DayPolicyEvaluator(new AttendancePolicyParamsCodec(VALIDATOR));

    // ---- the guarantee the whole feature rests on --------------------------

    @Test
    @DisplayName("no rules leaves the day byte-identical and writes no trace - the no-op path every unconfigured company takes")
    void noRulesChangesNothing() {
        DayContext context = worked("09:22", "18:30", AttendanceStatus.PRESENT);

        DayPolicyResult result = evaluator.apply(context, ResolvedPolicy.NONE);

        assertThat(result.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(result.lateMinutes()).isEqualTo(context.lateMinutes());
        assertThat(result.overtimeMinutes()).isEqualTo(context.overtimeMinutes());
        assertThat(result.compOffCredit()).isEqualByComparingTo("0");
        assertThat(result.trace()).isEmpty();
    }

    // ---- A: LATE_ARRIVAL ---------------------------------------------------

    @Test
    @DisplayName("scenario A: arriving at exactly the 15th minute is not late; the 16th is a half day")
    void lateArrivalBoundaryIsInclusive() {
        ResolvedPolicy policy = policy(RuleType.LATE_ARRIVAL,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        // 09:15 exactly - max(0, firstIn - (start + grace)) = 0, not late.
        DayPolicyResult onTime = evaluator.apply(worked("09:15", "18:05", AttendanceStatus.PRESENT), policy);
        assertThat(onTime.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(onTime.lateMinutes()).isZero();
        assertThat(onTime.trace()).isEmpty();

        // 09:16 - one minute past.
        DayPolicyResult late = evaluator.apply(worked("09:16", "18:30", AttendanceStatus.PRESENT), policy);
        assertThat(late.status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(late.lateMinutes()).isEqualTo(1);
        assertThat(late.trace()).singleElement().satisfies(row -> {
            assertThat(row.getStatusBefore()).isEqualTo(AttendanceStatus.PRESENT);
            assertThat(row.getStatusAfter()).isEqualTo(AttendanceStatus.HALF_DAY);
            assertThat(row.getExplanation())
                    .contains("1 min beyond a 15 min grace")
                    .contains("LATE_ARRIVAL v1 scoped CATEGORY=STAFF");
        });
    }

    @Test
    @DisplayName("lateness may only lower a day - an already-absent day is not raised to the half-day penalty")
    void lateArrivalNeverRaisesADay() {
        ResolvedPolicy policy = policy(RuleType.LATE_ARRIVAL,
                "{\"graceMinutes\":5,\"penaltyStatus\":\"HALF_DAY\"}");

        DayPolicyResult result = evaluator.apply(worked("11:00", "12:00", AttendanceStatus.ABSENT), policy);

        assertThat(result.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(result.lateMinutes()).isEqualTo(115);
        assertThat(result.trace()).isEmpty();
    }

    // ---- E: SHORT_HOURS ----------------------------------------------------

    @Test
    @DisplayName("scenario E: absolute-minute cutoffs are inclusive at the threshold, matching today's >= semantics")
    void shortHoursAbsoluteBoundaries() {
        ResolvedPolicy policy = policy(RuleType.SHORT_HOURS,
                "{\"basis\":\"ABSOLUTE_MINUTES\",\"fullDayValue\":240,\"halfDayValue\":120}");

        assertThat(applyWorkedMinutes(245, policy).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(applyWorkedMinutes(240, policy).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(applyWorkedMinutes(239, policy).status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(applyWorkedMinutes(120, policy).status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(applyWorkedMinutes(119, policy).status()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("percent basis measures against the shift's paid minutes, so 75/40 of 480 is 360 and 192")
    void shortHoursPercentBoundaries() {
        ResolvedPolicy policy = policy(RuleType.SHORT_HOURS,
                "{\"basis\":\"PERCENT_OF_SHIFT\",\"fullDayValue\":75,\"halfDayValue\":40}");

        assertThat(applyWorkedMinutes(360, policy).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(applyWorkedMinutes(359, policy).status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(applyWorkedMinutes(192, policy).status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(applyWorkedMinutes(191, policy).status()).isEqualTo(AttendanceStatus.ABSENT);
    }

    // ---- F: OVERTIME -------------------------------------------------------

    @Test
    @DisplayName("scenario F: overtime below the minimum earns nothing; above it rounds down to whole blocks")
    void overtimeMinimumAndRounding() {
        ResolvedPolicy policy = policy(RuleType.OVERTIME,
                "{\"payable\":true,\"minimumMinutes\":30,\"roundingBlockMinutes\":30,\"rounding\":\"DOWN\"}");

        assertThat(overtimeOf(20, policy)).isZero();      // below the 30 min minimum
        assertThat(overtimeOf(29, policy)).isZero();      // boundary: one short
        assertThat(overtimeOf(30, policy)).isEqualTo(30); // boundary: exactly the minimum, one block
        assertThat(overtimeOf(35, policy)).isEqualTo(30); // floor(35/30) = 1
        assertThat(overtimeOf(110, policy)).isEqualTo(90); // floor(110/30) = 3
        assertThat(overtimeOf(120, policy)).isEqualTo(120); // floor(120/30) = 4
    }

    @Test
    @DisplayName("payable:false zeroes the day's overtime outright - how 'overtime for workers only' is expressed at company scope")
    void overtimeNotPayableZeroesIt() {
        ResolvedPolicy policy = policy(RuleType.OVERTIME,
                "{\"payable\":false,\"minimumMinutes\":0,\"roundingBlockMinutes\":1,\"rounding\":\"DOWN\"}");

        DayPolicyResult result = evaluator.apply(workedMinutes(590, AttendanceStatus.PRESENT), policy);

        assertThat(result.overtimeMinutes()).isZero();
        assertThat(result.trace()).singleElement().satisfies(row ->
                assertThat(row.getExplanation()).contains("not payable for this population"));
    }

    // ---- H: MISSING_PUNCH --------------------------------------------------

    @Test
    @DisplayName("scenario H: a lone punch at or before the grace is a half day; one minute past stays INVALID_PUNCH")
    void missingPunchBoundary() {
        ResolvedPolicy policy = policy(RuleType.MISSING_PUNCH,
                "{\"fallbackStatus\":\"HALF_DAY\",\"onTimeGraceMinutes\":15}");

        assertThat(evaluator.apply(lonePunch("08:58"), policy).status())
                .isEqualTo(AttendanceStatus.HALF_DAY);
        // 09:15 exactly - inclusive.
        assertThat(evaluator.apply(lonePunch("09:15"), policy).status())
                .isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(evaluator.apply(lonePunch("09:16"), policy).status())
                .isEqualTo(AttendanceStatus.INVALID_PUNCH);
        // A lone punch near the shift's end is more likely a missed entry, but
        // the device has no in/out flag and guessing would invent evidence.
        assertThat(evaluator.apply(lonePunch("17:40"), policy).status())
                .isEqualTo(AttendanceStatus.INVALID_PUNCH);
    }

    @Test
    @DisplayName("the rescued day's explanation names the punch, the cutoff and the rule - the answer to a dispute six months later")
    void missingPunchExplanationIsReadable() {
        ResolvedPolicy policy = policy(RuleType.MISSING_PUNCH,
                "{\"fallbackStatus\":\"HALF_DAY\",\"onTimeGraceMinutes\":15}");

        DayPolicyResult result = evaluator.apply(lonePunch("08:58"), policy);

        assertThat(result.trace()).singleElement().satisfies(row ->
                assertThat(row.getExplanation())
                        .contains("single punch at 08:58")
                        .contains("at or before 09:15")
                        .contains("15 min grace")
                        .contains("instead of INVALID_PUNCH")
                        .contains("MISSING_PUNCH v1 scoped CATEGORY=STAFF"));
    }

    // ---- G: DAY_OFF_WORK ---------------------------------------------------

    @Test
    @DisplayName("scenario G: working a weekly off credits comp-off and zeroes the overtime it would otherwise have booked")
    void dayOffWorkCreditsCompOff() {
        ResolvedPolicy policy = policy(RuleType.DAY_OFF_WORK,
                "{\"onWeeklyOff\":\"COMP_OFF_CREDIT\",\"onHoliday\":\"OVERTIME_PAY\","
                        + "\"fullCreditMinutes\":240,\"halfCreditMinutes\":120}");

        DayPolicyResult result = evaluator.apply(dayOff(485, true), policy);

        assertThat(result.compOffCredit()).isEqualByComparingTo("1");
        assertThat(result.overtimeMinutes()).isZero();
        assertThat(result.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(result.trace()).singleElement().satisfies(row ->
                assertThat(row.getExplanation()).contains("comp-off credit"));
    }

    @Test
    @DisplayName("a holiday configured for overtime pay keeps today's behaviour, so one rule can treat the two day types differently")
    void dayOffWorkTreatsHolidayAndWeeklyOffSeparately() {
        ResolvedPolicy policy = policy(RuleType.DAY_OFF_WORK,
                "{\"onWeeklyOff\":\"COMP_OFF_CREDIT\",\"onHoliday\":\"OVERTIME_PAY\","
                        + "\"fullCreditMinutes\":240,\"halfCreditMinutes\":120}");

        DayPolicyResult holiday = evaluator.apply(dayOff(485, false), policy);

        assertThat(holiday.compOffCredit()).isEqualByComparingTo("0");
        assertThat(holiday.overtimeMinutes()).isEqualTo(5);
        assertThat(holiday.trace()).isEmpty();
    }

    @Test
    @DisplayName("a short stint on a day off earns half a credit, and less than that earns none")
    void compOffCreditTiers() {
        ResolvedPolicy policy = policy(RuleType.DAY_OFF_WORK,
                "{\"onWeeklyOff\":\"COMP_OFF_CREDIT\",\"onHoliday\":\"COMP_OFF_CREDIT\","
                        + "\"fullCreditMinutes\":240,\"halfCreditMinutes\":120}");

        assertThat(evaluator.apply(dayOff(240, true), policy).compOffCredit()).isEqualByComparingTo("1");
        assertThat(evaluator.apply(dayOff(239, true), policy).compOffCredit()).isEqualByComparingTo("0.5");
        assertThat(evaluator.apply(dayOff(120, true), policy).compOffCredit()).isEqualByComparingTo("0.5");
        assertThat(evaluator.apply(dayOff(119, true), policy).compOffCredit()).isEqualByComparingTo("0");
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    @DisplayName("SHORT_HOURS runs before LATE_ARRIVAL, so lateness downgrades the status hours already decided")
    void shortHoursRunsBeforeLateArrival() {
        ResolvedPolicy policy = new ResolvedPolicy(Map.of(
                RuleType.SHORT_HOURS, rule(RuleType.SHORT_HOURS,
                        "{\"basis\":\"ABSOLUTE_MINUTES\",\"fullDayValue\":240,\"halfDayValue\":120}"),
                RuleType.LATE_ARRIVAL, rule(RuleType.LATE_ARRIVAL,
                        "{\"graceMinutes\":5,\"penaltyStatus\":\"ABSENT\"}")));

        // 250 worked minutes clears the full-day cutoff, so SHORT_HOURS says
        // PRESENT; arriving at 11:00 is 115 minutes late, so LATE_ARRIVAL then
        // takes it all the way to ABSENT.
        DayContext context = new DayContext("SE10012", SEP_3, shift(), 2,
                SEP_3.atTime(11, 0), SEP_3.atTime(16, 10),
                250, 480, 0, 0, 0, false, false, AttendanceStatus.ABSENT);

        DayPolicyResult result = evaluator.apply(context, policy);

        assertThat(result.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(result.trace()).hasSize(2);
        assertThat(result.trace().get(0).getRuleType()).isEqualTo(RuleType.SHORT_HOURS);
        assertThat(result.trace().get(0).getStatusAfter()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(result.trace().get(1).getRuleType()).isEqualTo(RuleType.LATE_ARRIVAL);
        assertThat(result.trace().get(1).getStatusAfter()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("DAY_OFF_WORK runs before OVERTIME, so an overtime rule cannot resurrect hours comp-off already zeroed")
    void dayOffWorkRunsBeforeOvertime() {
        ResolvedPolicy policy = new ResolvedPolicy(Map.of(
                RuleType.DAY_OFF_WORK, rule(RuleType.DAY_OFF_WORK,
                        "{\"onWeeklyOff\":\"COMP_OFF_CREDIT\",\"onHoliday\":\"COMP_OFF_CREDIT\","
                                + "\"fullCreditMinutes\":240,\"halfCreditMinutes\":120}"),
                RuleType.OVERTIME, rule(RuleType.OVERTIME,
                        "{\"payable\":true,\"minimumMinutes\":0,\"roundingBlockMinutes\":30,\"rounding\":\"DOWN\"}")));

        DayPolicyResult result = evaluator.apply(dayOff(700, true), policy);

        assertThat(result.overtimeMinutes()).isZero();
        assertThat(result.compOffCredit()).isEqualByComparingTo("1");
    }

    // ---- helpers -----------------------------------------------------------

    private DayPolicyResult applyWorkedMinutes(long workedMinutes, ResolvedPolicy policy) {
        return evaluator.apply(workedMinutes(workedMinutes, AttendanceStatus.PRESENT), policy);
    }

    private long overtimeOf(long overtimeMinutes, ResolvedPolicy policy) {
        DayContext context = new DayContext("SE10012", SEP_3, shift(), 2,
                SEP_3.atTime(9, 0), SEP_3.atTime(18, 0),
                480 + overtimeMinutes, 480, 0, 0, overtimeMinutes, false, false,
                AttendanceStatus.PRESENT);
        return evaluator.apply(context, policy).overtimeMinutes();
    }

    private DayContext worked(String in, String out, AttendanceStatus baseStatus) {
        LocalDateTime firstIn = SEP_3.atTime(LocalTime.parse(in));
        LocalDateTime lastOut = SEP_3.atTime(LocalTime.parse(out));
        long worked = java.time.Duration.between(firstIn, lastOut).toMinutes() - 60;
        return new DayContext("SE10012", SEP_3, shift(), 2, firstIn, lastOut,
                worked, 480, 0, 0, Math.max(0, worked - 480), false, false, baseStatus);
    }

    private DayContext workedMinutes(long worked, AttendanceStatus baseStatus) {
        return new DayContext("SE10012", SEP_3, shift(), 2,
                SEP_3.atTime(9, 0), SEP_3.atTime(9, 0).plusMinutes(worked + 60),
                worked, 480, 0, 0, Math.max(0, worked - 480), false, false, baseStatus);
    }

    private DayContext dayOff(long worked, boolean weekOff) {
        return new DayContext("SE10012", SEP_3, shift(), 2,
                SEP_3.atTime(9, 5), SEP_3.atTime(9, 5).plusMinutes(worked + 60),
                worked, 480, 0, 0, Math.max(0, worked - 480), weekOff, !weekOff,
                AttendanceStatus.PRESENT);
    }

    private DayContext lonePunch(String at) {
        return new DayContext("SE10012", SEP_3, shift(), 1,
                SEP_3.atTime(LocalTime.parse(at)), null,
                0, 480, 0, 0, 0, false, false, AttendanceStatus.INVALID_PUNCH);
    }

    private Shift shift() {
        return Shift.builder().shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(0).overtimeWindowMinutes(240)
                .build();
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

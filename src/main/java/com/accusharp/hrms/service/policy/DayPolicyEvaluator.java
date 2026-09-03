package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.policy.AttendancePolicyParams.DayOffWork;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.LateArrival;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.MissingPunch;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.Overtime;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams.ShortHours;
import com.accusharp.hrms.entity.AttendancePolicyApplication;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies the day-scoped policy rules to one already-computed day.
 *
 * <p>Every rule here is a <b>pure function of a single day</b>. Nothing reads
 * another day, a running counter, or the clock. That is what makes regenerating
 * one day in isolation give the same answer as regenerating the whole month,
 * and it is the property that separates these rules from the month-scoped ones
 * in {@link MonthPolicyEvaluator}, which cannot be evaluated here at all.
 *
 * <h2>Order is fixed, and it matters</h2>
 *
 * <pre>
 *   MISSING_PUNCH -> SHORT_HOURS -> LATE_ARRIVAL -> DAY_OFF_WORK -> OVERTIME
 *      (status)       (status)       (status)      (OT, comp-off)    (OT)
 * </pre>
 *
 * <p>{@code MISSING_PUNCH} first because it works on the one-punch branch the
 * others never see. {@code SHORT_HOURS} before {@code LATE_ARRIVAL} because
 * lateness modifies a status that hours have already decided. {@code OVERTIME}
 * last because it reads an outcome {@code DAY_OFF_WORK} may have zeroed. The
 * order is {@link RuleType}'s declaration order, asserted by
 * {@code DayPolicyEvaluatorTest}.
 *
 * <h2>The monotonicity invariant</h2>
 *
 * <p>After every rule has run, the day is worth <b>no more</b> than the punches
 * earned it - with exactly one exception, {@code MISSING_PUNCH}, which raises
 * {@code INVALID_PUNCH} to a fallback status because rescuing a day the device
 * broke is its entire purpose. Every other rule may only take value away. A
 * rule that could raise a day above what its punches earned would be a way to
 * configure unearned pay, and no scenario needs one.
 */
@Service
@RequiredArgsConstructor
public class DayPolicyEvaluator {

    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final AttendancePolicyParamsCodec codec;

    /**
     * One day's inputs, as the calculation service already has them - raw
     * minutes rather than the rounded hours the response carries, so a rule
     * never has to reverse a rounding to get back to what it needs.
     */
    public record DayContext(String userId, LocalDate shiftDate, Shift shift,
                             int punchCount, LocalDateTime firstIn, LocalDateTime lastOut,
                             long workedMinutes, long shiftMinutes,
                             int lateMinutes, int earlyExitMinutes, long overtimeMinutes,
                             boolean weekOff, boolean holiday,
                             AttendanceStatus baseStatus) {
    }

    /**
     * @param compOffCredit days of compensatory off this day earned, or zero
     * @param trace         one row per rule that actually changed something.
     *                      A rule that resolved but did not fire leaves no row:
     *                      the trace answers "why is this day what it is", and
     *                      a rule that changed nothing is not part of the answer.
     */
    public record DayPolicyResult(AttendanceStatus status, int lateMinutes, long overtimeMinutes,
                                  BigDecimal compOffCredit,
                                  List<AttendancePolicyApplication> trace) {

        static DayPolicyResult unchanged(DayContext context) {
            return new DayPolicyResult(context.baseStatus(), context.lateMinutes(),
                    context.overtimeMinutes(), BigDecimal.ZERO, List.of());
        }
    }

    /**
     * Applies whatever resolved. Returns the day unchanged, with an empty
     * trace, when nothing did - the path every company that has configured no
     * rules takes on every day of every month, and the reason this feature
     * cannot alter their output.
     */
    public DayPolicyResult apply(DayContext context, ResolvedPolicy policy) {
        if (policy.isEmpty() || !policy.hasAnyDayRule()) {
            return DayPolicyResult.unchanged(context);
        }

        AttendanceStatus status = context.baseStatus();
        int lateMinutes = context.lateMinutes();
        long overtimeMinutes = context.overtimeMinutes();
        BigDecimal compOffCredit = BigDecimal.ZERO;
        List<AttendancePolicyApplication> trace = new ArrayList<>();

        boolean dayOff = context.weekOff() || context.holiday();

        // ---- MISSING_PUNCH: the one-punch branch, and the only rule that may raise a day
        if (context.punchCount() == 1 && context.baseStatus() == AttendanceStatus.INVALID_PUNCH) {
            Optional<AttendancePolicyRule> rule = policy.rule(RuleType.MISSING_PUNCH);
            if (rule.isPresent()) {
                MissingPunch params = codec.parse(rule.get(), MissingPunch.class);
                LocalDateTime latestEntry = shiftStart(context).plusMinutes(params.onTimeGraceMinutes());
                // Inclusive: a punch at exactly shiftStart + grace qualifies, the
                // same boundary lateMinutes already uses.
                if (context.firstIn() != null && !context.firstIn().isAfter(latestEntry)) {
                    AttendanceStatus after = params.fallbackStatus().toAttendanceStatus();
                    // One literal, not a concatenation: `"a" + "b".formatted(x)`
                    // binds .formatted to the second literal alone and silently
                    // misaligns every argument.
                    trace.add(statusChange(context, rule.get(), status, after,
                            "%s: single punch at %s is a plausible entry (at or before %s, a %d min grace on a %s shift) - treated as %s instead of INVALID_PUNCH, rule %s"
                                    .formatted(after, time(context.firstIn()), time(latestEntry),
                                            params.onTimeGraceMinutes(), time(shiftStart(context)),
                                            after, rule.get().ruleLabel())));
                    status = after;
                }
            }
            // Nothing else can apply to a day with one punch: there are no hours
            // to threshold, no exit to measure, and no overtime to round.
            return new DayPolicyResult(status, lateMinutes, overtimeMinutes, compOffCredit, List.copyOf(trace));
        }

        if (context.punchCount() < 2) {
            return DayPolicyResult.unchanged(context);
        }

        // ---- SHORT_HOURS: re-decides the worked status for this population
        if (!dayOff) {
            Optional<AttendancePolicyRule> rule = policy.rule(RuleType.SHORT_HOURS);
            if (rule.isPresent()) {
                ShortHours params = codec.parse(rule.get(), ShortHours.class);
                long fullMinutes = thresholdMinutes(params.basis(), params.fullDayValue(), context.shiftMinutes());
                long halfMinutes = thresholdMinutes(params.basis(), params.halfDayValue(), context.shiftMinutes());

                AttendanceStatus after = context.workedMinutes() >= fullMinutes ? AttendanceStatus.PRESENT
                        : context.workedMinutes() >= halfMinutes ? AttendanceStatus.HALF_DAY
                        : AttendanceStatus.ABSENT;

                if (after != status) {
                    trace.add(statusChange(context, rule.get(), status, after,
                            "%s: worked %d min against a %d min full-day and %d min half-day cutoff, rule %s"
                                    .formatted(after, context.workedMinutes(), fullMinutes, halfMinutes,
                                            rule.get().ruleLabel())));
                    status = after;
                }
            }
        }

        // ---- LATE_ARRIVAL: re-measures lateness, then may downgrade the day
        if (!dayOff) {
            Optional<AttendancePolicyRule> rule = policy.rule(RuleType.LATE_ARRIVAL);
            if (rule.isPresent()) {
                LateArrival params = codec.parse(rule.get(), LateArrival.class);
                LocalDateTime graceEnd = shiftStart(context).plusMinutes(params.graceMinutes());
                int late = (int) Math.max(0, Duration.between(graceEnd, context.firstIn()).toMinutes());
                lateMinutes = late;

                if (late > 0) {
                    AttendanceStatus penalty = params.penaltyStatus().toAttendanceStatus();
                    // Only ever downward. A day already worth less than the
                    // penalty keeps its own, lower value.
                    if (penalty.isWorthLessThan(status)) {
                        trace.add(statusChange(context, rule.get(), status, penalty,
                                "%s: in %s, %d min beyond a %d min grace on a %s shift, rule %s"
                                        .formatted(penalty, time(context.firstIn()), late,
                                                params.graceMinutes(), time(shiftStart(context)),
                                                rule.get().ruleLabel())));
                        status = penalty;
                    }
                }
            }
        }

        // ---- DAY_OFF_WORK: what working a weekly off or holiday earns
        if (dayOff) {
            Optional<AttendancePolicyRule> rule = policy.rule(RuleType.DAY_OFF_WORK);
            if (rule.isPresent()) {
                DayOffWork params = codec.parse(rule.get(), DayOffWork.class);
                DayOffWork.Treatment treatment =
                        context.weekOff() ? params.onWeeklyOff() : params.onHoliday();

                if (treatment == DayOffWork.Treatment.COMP_OFF_CREDIT) {
                    BigDecimal credit = context.workedMinutes() >= params.fullCreditMinutes() ? BigDecimal.ONE
                            : context.workedMinutes() >= params.halfCreditMinutes() ? HALF
                            : BigDecimal.ZERO;
                    long overtimeBefore = overtimeMinutes;
                    overtimeMinutes = 0;
                    compOffCredit = credit;

                    trace.add(AttendancePolicyApplication.builder()
                            .userId(context.userId()).attendanceDate(context.shiftDate())
                            .ruleId(rule.get().getId()).ruleType(rule.get().getRuleType())
                            .ruleVersion(rule.get().getVersion())
                            .scope(rule.get().getScope()).scopeRef(rule.get().getScopeRef())
                            .overtimeBefore(hours(overtimeBefore)).overtimeAfter(hours(0))
                            .compOffCredit(credit.setScale(2, RoundingMode.HALF_UP))
                            .explanation("worked %d min on a %s: %s day comp-off credit instead of %s h overtime, rule %s"
                                    .formatted(context.workedMinutes(),
                                            context.weekOff() ? "weekly off" : "holiday",
                                            credit.toPlainString(), hours(overtimeBefore).toPlainString(),
                                            rule.get().ruleLabel()))
                            .build());
                }
            }
        }

        // ---- OVERTIME: eligibility and rounding, last because DAY_OFF_WORK may have zeroed it
        Optional<AttendancePolicyRule> overtimeRule = policy.rule(RuleType.OVERTIME);
        if (overtimeRule.isPresent() && overtimeMinutes > 0) {
            Overtime params = codec.parse(overtimeRule.get(), Overtime.class);
            long before = overtimeMinutes;
            long after = !params.payable() || before < params.minimumMinutes()
                    ? 0
                    : (before / params.roundingBlockMinutes()) * params.roundingBlockMinutes();

            if (after != before) {
                overtimeMinutes = after;
                trace.add(AttendancePolicyApplication.builder()
                        .userId(context.userId()).attendanceDate(context.shiftDate())
                        .ruleId(overtimeRule.get().getId()).ruleType(overtimeRule.get().getRuleType())
                        .ruleVersion(overtimeRule.get().getVersion())
                        .scope(overtimeRule.get().getScope()).scopeRef(overtimeRule.get().getScopeRef())
                        .overtimeBefore(hours(before)).overtimeAfter(hours(after))
                        .explanation(overtimeExplanation(params, before, after, overtimeRule.get()))
                        .build());
            }
        }

        return new DayPolicyResult(status, lateMinutes, overtimeMinutes, compOffCredit, List.copyOf(trace));
    }

    private String overtimeExplanation(Overtime params, long before, long after, AttendancePolicyRule rule) {
        if (!params.payable()) {
            return "overtime %s h earned but not payable for this population, rule %s"
                    .formatted(hours(before).toPlainString(), rule.ruleLabel());
        }
        if (after == 0) {
            return "overtime %d min is below the %d min minimum, rule %s"
                    .formatted(before, params.minimumMinutes(), rule.ruleLabel());
        }
        return "overtime %d min rounded down to %d x %d min blocks = %s h, rule %s"
                .formatted(before, after / params.roundingBlockMinutes(), params.roundingBlockMinutes(),
                        hours(after).toPlainString(), rule.ruleLabel());
    }

    /**
     * A percentage basis is measured against the shift's paid minutes; absolute
     * minutes are taken as they stand. "Below four hours is a half day" is not a
     * percentage of anything, and forcing it to be one would quietly mean
     * different things on an eight-hour and a twelve-hour shift.
     */
    private long thresholdMinutes(ShortHours.Basis basis, BigDecimal value, long shiftMinutes) {
        if (basis == ShortHours.Basis.ABSOLUTE_MINUTES) {
            return value.setScale(0, RoundingMode.HALF_UP).longValue();
        }
        return BigDecimal.valueOf(Math.max(shiftMinutes, 1))
                .multiply(value)
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .longValue();
    }

    private AttendancePolicyApplication statusChange(DayContext context, AttendancePolicyRule rule,
                                                     AttendanceStatus before, AttendanceStatus after,
                                                     String explanation) {
        return AttendancePolicyApplication.builder()
                .userId(context.userId()).attendanceDate(context.shiftDate())
                .ruleId(rule.getId()).ruleType(rule.getRuleType()).ruleVersion(rule.getVersion())
                .scope(rule.getScope()).scopeRef(rule.getScopeRef())
                .statusBefore(before).statusAfter(after)
                .explanation(explanation)
                .build();
    }

    private LocalDateTime shiftStart(DayContext context) {
        return context.shiftDate().atTime(context.shift().getStartTime());
    }

    private String time(LocalDateTime at) {
        return at.toLocalTime().toString();
    }

    private BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
    }
}

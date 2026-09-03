package com.accusharp.hrms.dto.policy;

import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The typed parameter set for each {@link RuleType} - one record per type, and
 * no other shape a rule's {@code params} column may take.
 *
 * <p>Every record here is bean-validated at the API boundary and again before
 * it is stored, so the bounds below are enforced on write rather than
 * discovered during a payroll run. This is the whole reason the catalog is
 * typed: a company can configure any numbers it likes within these bounds, and
 * cannot configure a rule that has no defined meaning.
 *
 * <p>The nested enums are deliberately tiny. They are the fixed vocabulary a
 * client composes from - unlimited rules, unlimited populations, unlimited
 * numbers, but a bounded set of <em>kinds</em> of consequence. Widening one is a
 * code change with a test, which is the correct bar for a new way of computing
 * somebody's salary.
 */
public final class AttendancePolicyParams {

    private AttendancePolicyParams() {
    }

    /** The record type each {@link RuleType}'s {@code params} binds to. */
    public static Class<? extends Params> typeOf(RuleType ruleType) {
        return switch (ruleType) {
            case MISSING_PUNCH -> MissingPunch.class;
            case SHORT_HOURS -> ShortHours.class;
            case LATE_ARRIVAL -> LateArrival.class;
            case DAY_OFF_WORK -> DayOffWork.class;
            case OVERTIME -> Overtime.class;
            case EARLY_EXIT_BUDGET -> EarlyExitBudget.class;
            case LATE_MARK_ACCUMULATION -> LateMarkAccumulation.class;
        };
    }

    /** Marker for the seven records below, so the resolver can hold one without casting to Object. */
    public sealed interface Params
            permits MissingPunch, ShortHours, LateArrival, DayOffWork, Overtime,
                    EarlyExitBudget, LateMarkAccumulation {
    }

    // ---- DAY-scoped --------------------------------------------------------

    /**
     * @param fallbackStatus what a lone, plausible entry punch is worth instead
     *                       of {@code INVALID_PUNCH}. {@code PRESENT} is not
     *                       offered: a full day's pay off a single punch, with
     *                       no evidence the employee stayed, is the kind of
     *                       configuration that should require a code change to
     *                       express.
     * @param onTimeGraceMinutes how late the lone punch may be and still read as
     *                       an entry. Inclusive - a punch at exactly
     *                       {@code shiftStart + grace} qualifies, matching how
     *                       {@code lateMinutes} already treats its own boundary.
     */
    public record MissingPunch(
            @NotNull FallbackStatus fallbackStatus,
            @Min(0) @Max(720) int onTimeGraceMinutes
    ) implements Params {

        /** Deliberately not {@code AttendanceStatus} - only these two are configurable. */
        public enum FallbackStatus {
            HALF_DAY, ABSENT;

            public AttendanceStatus toAttendanceStatus() {
                return this == HALF_DAY ? AttendanceStatus.HALF_DAY : AttendanceStatus.ABSENT;
            }
        }
    }

    /**
     * Replaces {@code AttendanceRule}'s two company-wide percentages for this
     * population.
     *
     * @param basis whether the two values below are percentages of the shift's
     *              paid minutes, or absolute minutes. "Below 4h is a half day"
     *              is not a percentage of anything, and expressing it as one
     *              would silently mean different things on a 8h and a 12h shift.
     */
    public record ShortHours(
            @NotNull Basis basis,
            @NotNull @DecimalMin("0") BigDecimal fullDayValue,
            @NotNull @DecimalMin("0") BigDecimal halfDayValue
    ) implements Params {

        public enum Basis { PERCENT_OF_SHIFT, ABSOLUTE_MINUTES }
    }

    /**
     * @param graceMinutes tolerance before an arrival counts as late, replacing
     *                     {@code Shift.graceMinutes} for this population.
     *                     Inclusive: arriving at exactly
     *                     {@code shiftStart + graceMinutes} is not late, which
     *                     is what the existing
     *                     {@code max(0, firstIn - (start + grace))} already
     *                     means and is not reinterpreted here.
     * @param penaltyStatus what the day drops to once late. May only ever lower
     *                     the day's value.
     */
    public record LateArrival(
            @Min(0) @Max(720) int graceMinutes,
            @NotNull PenaltyStatus penaltyStatus
    ) implements Params {

        public enum PenaltyStatus {
            HALF_DAY, ABSENT;

            public AttendanceStatus toAttendanceStatus() {
                return this == HALF_DAY ? AttendanceStatus.HALF_DAY : AttendanceStatus.ABSENT;
            }
        }
    }

    /**
     * @param fullCreditMinutes worked minutes earning a whole comp-off day
     * @param halfCreditMinutes worked minutes earning half a day; below this,
     *                          nothing is credited
     */
    public record DayOffWork(
            @NotNull Treatment onWeeklyOff,
            @NotNull Treatment onHoliday,
            @Min(0) int fullCreditMinutes,
            @Min(0) int halfCreditMinutes
    ) implements Params {

        public enum Treatment {
            /** Today's behaviour: the excess over the shift's paid hours books as overtime. */
            OVERTIME_PAY,
            /** Overtime is zeroed and the day earns a compensatory-off credit instead. */
            COMP_OFF_CREDIT
        }
    }

    /**
     * @param payable        false zeroes the day's overtime hours outright -
     *                       which is how "overtime for workers only" is
     *                       expressed: false at COMPANY, true at the category
     *                       that gets it.
     * @param minimumMinutes overtime below this earns nothing
     * @param roundingBlockMinutes overtime is rounded to whole blocks of this
     *                       size; 1 means no rounding
     */
    public record Overtime(
            boolean payable,
            @Min(0) @Max(1440) int minimumMinutes,
            @Min(1) @Max(480) int roundingBlockMinutes,
            @NotNull Rounding rounding
    ) implements Params {

        /**
         * Only {@code DOWN} today. Stated explicitly rather than left implicit
         * because every division in this codebase names its {@code
         * RoundingMode}, and overtime rounding is the one an employee is most
         * likely to check by hand.
         */
        public enum Rounding { DOWN }
    }

    // ---- MONTH-scoped ------------------------------------------------------

    /**
     * @param monthlyBudgetMinutes early-exit minutes an employee may spend in a
     *                       month before penalties start. Consumed in date
     *                       order; the day that exhausts the budget is covered
     *                       by it, and penalties begin on the next early exit.
     * @param penaltyDaysPerOccurrence LOP days each early exit costs once the
     *                       budget is spent - per occurrence, not per minute
     */
    public record EarlyExitBudget(
            @Min(0) @Max(100_000) int monthlyBudgetMinutes,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal penaltyDaysPerOccurrence
    ) implements Params {
    }

    /**
     * @param minimumLateMinutes how late a day must be to score a mark. 1 means
     *                       any lateness at all counts.
     * @param occurrencesPerPenalty marks per penalty - 3 for "three lates make a
     *                       half day". Never zero: it is a divisor.
     */
    public record LateMarkAccumulation(
            @Min(1) @Max(1440) int minimumLateMinutes,
            @Min(1) @Max(365) int occurrencesPerPenalty,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal penaltyLopDays
    ) implements Params {
    }
}

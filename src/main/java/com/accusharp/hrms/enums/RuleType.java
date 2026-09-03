package com.accusharp.hrms.enums;

/**
 * The attendance policy catalog: every kind of rule a company can configure,
 * and nothing else.
 *
 * <p>This is a fixed vocabulary on purpose. Attendance policy decides salary,
 * and the alternative - a formula or expression column evaluated at runtime -
 * is an injection surface, is untestable, cannot be migrated, and cannot be
 * explained back to an employee disputing a deduction six months later. Adding
 * a rule type here is a code change plus a migration. That is a feature: a new
 * way of computing somebody's pay deserves a code review, not a form. What a
 * company configures freely is how many rules it writes, which populations they
 * attach to, and what numbers they carry - all data, all unlimited.
 *
 * <p>Each constant carries its {@linkplain #evaluationScope() evaluation scope}
 * and the {@code params} record its JSON deserialises into. The params are
 * <b>never</b> read as a loose map: they are bound to the record below and
 * bean-validated on write, and a blob that will not bind fails generation
 * rather than being silently skipped (see {@code AttendancePolicyParams}).
 *
 * <p><b>Declaration order is the evaluation order within a day.</b> {@link
 * #MISSING_PUNCH} first because it works on the one-punch branch the others
 * never see; {@link #SHORT_HOURS} before {@link #LATE_ARRIVAL} because lateness
 * modifies a status that hours have already decided; {@link #OVERTIME} last
 * because it reads an {@link #DAY_OFF_WORK} outcome that may have zeroed it.
 * Reordering this file changes what employees are paid.
 */
public enum RuleType {

    /**
     * A day holding exactly one punch reads {@code INVALID_PUNCH} today and
     * becomes loss of pay. Where the lone punch is a plausible entry - inside
     * the entry window - this awards a fallback status instead of writing the
     * day off.
     *
     * <p>The day's {@code invalidPunch} flag deliberately stays set: the
     * employee is paid for half a day, and HR still sees a device that needs
     * fixing. What the day is worth and what went wrong are two questions, the
     * same separation {@code recordStatus} already has from {@code locked}.
     */
    MISSING_PUNCH(RuleEvaluationScope.DAY),

    /**
     * The full-day/half-day cutoffs, per population. Overrides {@code
     * AttendanceRule}'s two company-wide percentages for the employees it
     * resolves for, and can express them as absolute minutes instead - "below
     * 4h is a half day, below 2h is absent" is not a percentage of anything.
     */
    SHORT_HOURS(RuleEvaluationScope.DAY),

    /**
     * Lateness with a consequence. Today {@code lateMinutes} is recorded
     * against {@code Shift.graceMinutes} and then read by nothing at all - no
     * status, no day fraction, no LOP day, no rupee depends on it. This rule
     * re-measures it against its own grace and lets it downgrade the day.
     */
    LATE_ARRIVAL(RuleEvaluationScope.DAY),

    /**
     * What working a weekly off or a holiday earns: overtime pay, or a
     * compensatory-off credit instead.
     *
     * <p>Worth knowing what the alternative is actually worth. Overtime is
     * {@code max(0, worked - workingHours * 60)} on every day, day off
     * included, so a full eight-hour shift worked on a Sunday books five
     * minutes of overtime and adds nothing to {@code presentDays}. A comp-off
     * credit is not being traded against a day's pay; it is being traded
     * against 0.08 hours.
     */
    DAY_OFF_WORK(RuleEvaluationScope.DAY),

    /**
     * Overtime eligibility and rounding, per population. Can only ever reduce
     * the hours the punches earned - and the money still additionally requires
     * {@code Employee.overtimeEligible}, so a rule can never grant overtime to
     * someone the employee master says is ineligible.
     */
    OVERTIME(RuleEvaluationScope.DAY),

    /**
     * A monthly budget of early-exit minutes, consumed in date order. Each
     * early exit after the budget is exhausted costs a fixed fraction of a day.
     *
     * <p>Month-scoped because a budget cannot be spent day by day at generation
     * time: regenerating one day would spend minutes the rest of the month has
     * already spent.
     */
    EARLY_EXIT_BUDGET(RuleEvaluationScope.MONTH),

    /**
     * Nth late mark in a month costs a fraction of a day - "three lates make a
     * half-day LOP".
     *
     * <p>A day already downgraded by {@link #LATE_ARRIVAL} is <b>excluded</b>
     * from the count. Both rules configured for one population would otherwise
     * charge the employee twice for a single late arrival: once as a half day
     * on the day itself, and again as a mark toward this penalty.
     */
    LATE_MARK_ACCUMULATION(RuleEvaluationScope.MONTH);

    private final RuleEvaluationScope evaluationScope;

    RuleType(RuleEvaluationScope evaluationScope) {
        this.evaluationScope = evaluationScope;
    }

    public RuleEvaluationScope evaluationScope() {
        return evaluationScope;
    }

    public boolean isDayScoped() {
        return evaluationScope == RuleEvaluationScope.DAY;
    }

    public boolean isMonthScoped() {
        return evaluationScope == RuleEvaluationScope.MONTH;
    }
}

package com.accusharp.hrms.enums;

/**
 * When a rule can be evaluated - and the distinction is the crux of the whole
 * policy engine, because getting it wrong corrupts salaries silently.
 *
 * <p>A {@link #DAY} rule is a pure function of one day. It runs during
 * generation, inside {@code AttendanceCalculationService.calculateDay}, and
 * regenerating a single day in isolation gives the same answer as regenerating
 * the whole month.
 *
 * <p>A {@link #MONTH} rule is stateful and order-dependent - a budget consumed
 * day by day, an Nth-occurrence counter. It <b>cannot</b> be evaluated during
 * generation: regenerating day 12 alone would consume budget the other days
 * already consumed, and the same month would score differently depending on
 * which days had been touched since. It is instead replayed as a fold over the
 * month's stored days in date order, from a zero accumulator, every time the
 * summary is built - so the result is derived from scratch and never
 * incrementally mutated.
 */
public enum RuleEvaluationScope {
    DAY,
    MONTH
}

package com.accusharp.hrms.service.recovery;

import com.accusharp.hrms.entity.EmployeeRecovery;
import com.accusharp.hrms.enums.RecoveryType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * What one payroll period is going to recover, decided but not yet written.
 *
 * <p>Split from the writing on purpose: the amounts have to be known while
 * the payroll is still being built (they change {@code totalDeduction} and
 * therefore {@code netSalary}), but the ledger rows that record them can only
 * be written once the payroll row exists and has an id to point at. So
 * {@code RecoveryService.plan} decides, {@code PayrollService.build} folds
 * the numbers into the payroll and saves it, and
 * {@code RecoveryService.commit} then moves the balances - all inside
 * payroll's single transaction, so a failure anywhere leaves neither the
 * payroll nor the balances changed.
 */
public record RecoveryPlan(Map<RecoveryType, TypeOutcome> outcomes) {

    private static final int SCALE = 2;

    /** One type's outcome for this period. */
    public record TypeOutcome(
            RecoveryType type,
            /** Total outstanding across this type's active recoveries before this period ran. */
            BigDecimal opening,
            /** What was asked for: the caller's override if they gave one, otherwise the scheduled instalments. */
            BigDecimal requested,
            /** What the period could actually afford, and therefore what is deducted. */
            BigDecimal applied,
            /** Outstanding after this period - {@code opening - } the ledger-backed share of {@code applied}. */
            BigDecimal closing,
            /**
             * The share of {@code requested} that no existing recovery backs -
             * a fine or a one-off amount typed straight onto the generate
             * screen with nothing on file behind it. Recovered this period
             * like any other deduction; whatever the month cannot afford of
             * it is raised as a new recovery rather than dropped (see
             * {@code RecoveryService.commit}).
             */
            BigDecimal adHocRequested,
            BigDecimal adHocApplied,
            List<Allocation> allocations
    ) {

        /** Shortfall this period could not cover - stays owed and is taken from the next one. */
        public BigDecimal shortfall() {
            return requested.subtract(applied).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
        }

        /** The part of {@link #shortfall()} that has no recovery holding it yet, and so needs one raising. */
        public BigDecimal adHocShortfall() {
            return adHocRequested.subtract(adHocApplied).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** How much of one recovery's outstanding this period takes. */
    public record Allocation(EmployeeRecovery recovery, BigDecimal amount) {
    }

    public TypeOutcome outcome(RecoveryType type) {
        return outcomes.get(type);
    }

    public BigDecimal applied(RecoveryType type) {
        TypeOutcome outcome = outcomes.get(type);
        return outcome == null ? zero() : outcome.applied();
    }

    public BigDecimal opening(RecoveryType type) {
        TypeOutcome outcome = outcomes.get(type);
        return outcome == null ? zero() : outcome.opening();
    }

    public BigDecimal closing(RecoveryType type) {
        TypeOutcome outcome = outcomes.get(type);
        return outcome == null ? zero() : outcome.closing();
    }

    /** Every type's total deduction for the period. */
    public BigDecimal totalApplied() {
        return outcomes.values().stream()
                .map(TypeOutcome::applied)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Everything this period asked for but could not afford - shown on the payroll so a short recovery is visible rather than inferred. */
    public BigDecimal totalCarriedForward() {
        return outcomes.values().stream()
                .map(TypeOutcome::shortfall)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
}

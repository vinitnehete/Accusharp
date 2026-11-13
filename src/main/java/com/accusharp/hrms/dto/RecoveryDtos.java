package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.RecoveryStatus;
import com.accusharp.hrms.enums.RecoveryTransactionType;
import com.accusharp.hrms.enums.RecoveryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read models for the recovery ledger - loans, advances, fines and other recoverable amounts. */
public final class RecoveryDtos {

    private RecoveryDtos() {
    }

    /** One recovery with its live balance. */
    public record RecoveryRow(
            Long id,
            String employeeId,
            String employeeCode,
            String employeeName,
            String departmentName,
            RecoveryType type,
            RecoveryStatus status,
            BigDecimal principalAmount,
            BigDecimal installmentAmount,
            /** What has actually come back so far: principal minus outstanding, waivers and adjustments included. */
            BigDecimal recoveredAmount,
            BigDecimal outstandingAmount,
            int startMonth,
            int startYear,
            LocalDate issuedOn,
            String reason,
            String remarks,
            String issuedBy,
            Instant createdAt,
            Instant closedAt,
            /** Whole instalments still to run, rounded up - the last one is whatever is left, not a full instalment. */
            Integer remainingInstallments
    ) {
    }

    /** One movement on the ledger. */
    public record TransactionRow(
            Long id,
            Long recoveryId,
            RecoveryType type,
            RecoveryTransactionType transactionType,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            Long payrollId,
            Integer month,
            Integer year,
            Integer payrollRevision,
            String remarks,
            String recordedBy,
            Instant recordedAt
    ) {
    }

    /** Every recovery an employee has, their balances by type, and the full ledger behind them. */
    public record Statement(
            String employeeId,
            String employeeCode,
            String employeeName,
            List<TypeBalance> balances,
            BigDecimal totalOutstanding,
            List<RecoveryRow> recoveries,
            List<TransactionRow> transactions
    ) {
    }

    /** Everything owed under one {@link RecoveryType}. */
    public record TypeBalance(
            RecoveryType type,
            int activeCount,
            BigDecimal principalAmount,
            BigDecimal outstandingAmount,
            /** What the next payroll would take for this type, before affordability is applied. */
            BigDecimal scheduledInstallment
    ) {
    }

    /**
     * What payroll <em>would</em> recover for one employee in one period, per
     * type - the figures the generate screen pre-fills so HR sees the numbers
     * that are about to apply rather than typing them from memory.
     *
     * <p>Only what is scheduled, never what is affordable: whether the month
     * can actually cover it depends on earnings and statutory deductions that
     * aren't known until the payroll is built (see
     * {@code RecoveryService.applyRecoveries}).
     */
    public record Schedule(
            String employeeId,
            int month,
            int year,
            List<TypeBalance> balances,
            BigDecimal advanceDeduction,
            BigDecimal loanDeduction,
            BigDecimal fine,
            BigDecimal otherDeduction,
            BigDecimal totalScheduled,
            BigDecimal totalOutstanding
    ) {
    }
}

package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RecoveryStatus;
import com.accusharp.hrms.enums.RecoveryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Something the employee owes the company that payroll recovers over one or
 * more periods - a loan, a salary advance, a fine, or any other recoverable
 * amount (see {@link RecoveryType}).
 *
 * <p>This is the record that was missing: before it, {@code
 * Payroll.loanDeduction} and {@code Payroll.advanceDeduction} were numbers HR
 * retyped every month with nothing behind them - no principal, no running
 * balance, no way to answer "how much is left?" and nothing at all for the
 * whole-company {@code generate-all} run to deduct, which therefore recovered
 * nothing from anybody.
 *
 * <p>{@code outstandingAmount} is the live balance and the only mutable
 * figure here; every movement that changes it also writes a {@link
 * RecoveryTransaction}, so the balance is always reconcilable against an
 * append-only ledger rather than being the sole record of what happened.
 *
 * <p>Plain scalar {@code employeeId} (the {@code Employee.userId} business
 * key), not a JPA relation - same convention as {@link SalaryRevision} and
 * {@code AuditLog}. Tenant isolation comes from resolving that id through
 * {@code EmployeeService.getEntityByUserId} on the way in, exactly as
 * payroll itself does.
 */
@Entity
@Table(name = "employee_recovery",
        indexes = {
                @Index(name = "idx_recovery_employee", columnList = "employee_id"),
                @Index(name = "idx_recovery_employee_status", columnList = "employee_id,status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Employee.userId business key. */
    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecoveryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecoveryStatus status;

    /**
     * What was issued or levied - the opening balance this recovery started
     * from. Never changes once issued: a correction is an {@code ADJUSTMENT}
     * transaction against {@link #outstandingAmount}, so the original figure
     * stays visible.
     */
    @Column(name = "principal_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal principalAmount;

    /**
     * What payroll recovers each period. For a loan this is the monthly
     * instalment; for a fine or a one-off recovery it defaults to the whole
     * principal, so it is taken in full by the first payroll that can afford
     * it. Payroll never takes more than {@link #outstandingAmount}, and never
     * more than the period can afford - see
     * {@code RecoveryService.applyRecoveries}.
     */
    @Column(name = "installment_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal installmentAmount;

    /** What is still owed. Quota-minus-used is not workable here - a recovery is reduced by an unbounded number of partial repayments, waivers and reversals - so the balance is stored and the ledger reconciles it. */
    @Column(name = "outstanding_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal outstandingAmount;

    /**
     * First period payroll may recover from, so a loan disbursed on the 28th
     * isn't recovered from the salary that same month unless that is what was
     * agreed. Stored as month/year rather than a date because a payroll
     * period is a month, and comparing a date against one invites
     * off-by-one arguments about which side of the month a disbursement
     * falls.
     */
    @Column(name = "start_month", nullable = false)
    private Integer startMonth;

    @Column(name = "start_year", nullable = false)
    private Integer startYear;

    /** When the money was handed over, or the fine levied. */
    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    /** Free text: loan purpose, what the fine was for, what the recovery covers. */
    @Column(length = 300)
    private String reason;

    @Column(length = 300)
    private String remarks;

    /** Employee.userId or PlatformUser.username of whoever raised this - same actor convention as {@link SalaryRevision}. */
    @Column(name = "issued_by", length = 50)
    private String issuedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Set when the outstanding reached zero or the recovery was cancelled. */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Optimistic lock. Two payroll runs touching the same recovery at once -
     * a single {@code generate} racing a whole-company {@code generate-all},
     * or a retried bulk upload - would otherwise both read the same
     * outstanding and both deduct from it, recovering the instalment twice
     * against one balance. The second writer gets a clean version conflict
     * ({@code GlobalExceptionHandler} maps it to a 409) instead of a lost
     * update, exactly as {@link LeaveBalance} does for concurrent leave
     * approvals.
     */
    @Version
    private Long version;

    /** True when payroll should recover from this recovery in the given period. */
    public boolean isRecoverableIn(YearMonth period) {
        return status == RecoveryStatus.ACTIVE
                && outstandingAmount != null
                && outstandingAmount.signum() > 0
                && !period.isBefore(YearMonth.of(startYear, startMonth));
    }

    /** What this recovery asks for in a period, before affordability is considered: the instalment, capped at what is actually left. */
    public BigDecimal scheduledInstallment() {
        if (installmentAmount == null || outstandingAmount == null) {
            return BigDecimal.ZERO;
        }
        return installmentAmount.min(outstandingAmount).max(BigDecimal.ZERO);
    }
}

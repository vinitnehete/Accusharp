package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RecoveryTransactionType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on an {@link EmployeeRecovery}'s balance - append-only, never
 * edited or deleted, so "why is ₹4,200 still outstanding on this loan?" is
 * answerable from the rows rather than from anybody's memory.
 *
 * <p>The reason this exists rather than just a running balance on the
 * recovery itself is payroll's immutability. Regenerating a period does not
 * overwrite the old payroll - it supersedes it and writes a new revision (see
 * {@code PayrollService.regenerate}) - so the recovery the superseded
 * revision made has to be given back before the new one is computed, or every
 * regeneration silently recovers the instalment twice. Each {@code REPAYMENT}
 * carries the {@link #payrollId} that made it, which turns that reversal into
 * a lookup of what was actually taken instead of a re-derivation of what
 * <em>should</em> have been taken - the two are not the same number once
 * affordability, a mid-period salary revision or an attendance correction is
 * involved.
 *
 * <p>{@code balanceBefore}/{@code balanceAfter} are stored rather than
 * derived by replaying the ledger, so a single row is independently
 * meaningful in an export and a reconciliation gap is visible instead of
 * arithmetically impossible.
 */
@Entity
@Table(name = "recovery_transaction",
        indexes = {
                @Index(name = "idx_recovery_txn_recovery", columnList = "recovery_id"),
                @Index(name = "idx_recovery_txn_payroll", columnList = "payroll_id"),
                @Index(name = "idx_recovery_txn_employee_period", columnList = "employee_id,`year`,`month`")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code @JsonIgnore}: the ledger is served through
     * {@code RecoveryTransactionResponse}, and with
     * {@code spring.jpa.open-in-view=false} serializing this lazy proxy
     * directly would throw - the same reason {@code SalaryRule.company} and
     * {@code Holiday.company} carry the annotation.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_id", nullable = false)
    private EmployeeRecovery recovery;

    /** Denormalized from the recovery so the ledger can be queried and exported per employee without a join. */
    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecoveryTransactionType type;

    /** Always positive - {@link #type} says which way the balance moved. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /** The payroll row this repayment was taken by, or the one whose reversal this is. Null for ISSUE/WAIVER/ADJUSTMENT. */
    @Column(name = "payroll_id")
    private Long payrollId;

    /** Quoted for the same reason {@code Payroll} quotes them: reserved words on some databases. Null outside payroll-driven rows. */
    @Column(name = "`month`")
    private Integer month;

    @Column(name = "`year`")
    private Integer year;

    /** Which payroll revision took this, so a reversal is traceable to the exact superseded run. */
    @Column(name = "payroll_revision")
    private Integer payrollRevision;

    @Column(length = 300)
    private String remarks;

    /** Employee.userId or PlatformUser.username; null when payroll itself posted the row unattended. */
    @Column(name = "recorded_by", length = 50)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}

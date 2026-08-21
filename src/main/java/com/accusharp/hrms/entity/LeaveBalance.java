package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.LeaveType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Per employee, per calendar year, per leave type. {@code used} is the only
 * mutable figure - balance is always quota minus used.
 */
@Entity
@Table(name = "leave_balance",
        uniqueConstraints = @UniqueConstraint(name = "uk_leave_balance",
                columnNames = {"user_id", "leave_year", "leave_type"}),
        indexes = @Index(name = "idx_leave_balance_year", columnList = "leave_year"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal quota;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal used;

    /**
     * Optimistic lock: two concurrent leave approvals (or a retried request
     * that actually succeeded server-side the first time) both reading the
     * same balance and both consuming it would otherwise silently deduct
     * twice. The second writer here gets a clean {@code
     * ObjectOptimisticLockingFailureException} - mapped to a 409 by {@code
     * GlobalExceptionHandler} - instead of a lost update.
     */
    @Version
    private Long version;

    public BigDecimal available() {
        return quota.subtract(used);
    }
}

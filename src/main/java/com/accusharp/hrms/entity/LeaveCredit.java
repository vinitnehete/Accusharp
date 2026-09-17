package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One posting of days onto a leave balance - a month's earned leave, or a year's
 * carry-forward - with the reason for it.
 *
 * <p>The balance itself stays quota minus used; each posting moves the quota of
 * {@link #leaveYear}. This row is what makes that movement safe to repeat and
 * possible to explain:
 *
 * <ul>
 *   <li><b>One row per posting.</b> Unique per employee, leave type, kind and
 *       period, so reposting a month - payroll regenerated - replaces the row
 *       and moves the balance by the difference, instead of crediting the month
 *       twice.</li>
 *   <li><b>The reason travels with the number.</b> {@link #basis} says in plain
 *       words what the credit was computed from, which is what answers "why did
 *       I get 1.2 this month?" six months later.</li>
 * </ul>
 */
@Entity
@Table(name = "leave_credit",
        uniqueConstraints = @UniqueConstraint(name = "uk_leave_credit",
                columnNames = {"user_id", "leave_type", "kind", "credit_period"}),
        indexes = @Index(name = "idx_leave_credit_user_year", columnList = "user_id, leave_year"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveCreditKind kind;

    /**
     * {@code yyyy-MM} for a month's accrual, {@code yyyy} - the year closed - for
     * a carry-forward. Not {@code period}, which some SQL dialects reserve.
     */
    @Column(name = "credit_period", nullable = false, length = 7)
    private String period;

    /** The balance year these days were added to. */
    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal days;

    /** For an accrual: working days minus LOP, the figure the credit was computed from. */
    @Column(name = "days_counted", precision = 5, scale = 1)
    private BigDecimal daysCounted;

    /** For a carry-forward: what was over the cap and is due for payout. */
    @Column(name = "excess_days", precision = 5, scale = 1)
    private BigDecimal excessDays;

    /** For a carry-forward under a rule set to lapse: what was over the cap and was dropped. */
    @Column(name = "lapsed_days", precision = 5, scale = 1)
    private BigDecimal lapsedDays;

    @Column(nullable = false, length = 400)
    private String basis;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;
}

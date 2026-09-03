package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-through cache of a month's attendance, recomputed from device_logs and
 * the shift roster every time the monthly report runs. Not a source of truth -
 * payroll reads it, nothing edits it by hand.
 */
@Entity
@Table(name = "emp_monthly_attendance_summary",
        uniqueConstraints = @UniqueConstraint(name = "uk_monthly_summary",
                columnNames = {"user_id", "`month`"}),
        indexes = @Index(name = "idx_monthly_summary_month", columnList = "`month`"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyAttendanceSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /** ISO "yyyy-MM". Quoted because "month" is a reserved word on some databases. */
    @Column(name = "`month`", nullable = false, length = 7)
    private String month;

    @Column(name = "working_days", nullable = false)
    private long workingDays;

    @Column(name = "present_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal presentDays;

    @Column(name = "absent_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal absentDays;

    @Column(name = "half_days", nullable = false)
    private long halfDays;

    @Column(name = "leave_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal leaveDays;

    @Column(name = "holiday_days", nullable = false)
    private long holidayDays;

    @Column(name = "week_off_days", nullable = false)
    private long weekOffDays;

    @Column(name = "late_count", nullable = false)
    private long lateCount;

    @Column(name = "early_exit_count", nullable = false)
    private long earlyExitCount;

    @Column(name = "invalid_punches", nullable = false)
    private long invalidPunches;

    @Column(name = "total_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalHours;

    @Column(name = "overtime_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal overtimeHours;

    @Column(name = "lop_days", nullable = false, precision = 6, scale = 1)
    private BigDecimal lopDays;

    /**
     * The share of {@link #lopDays} that came from month-scoped attendance
     * policy rules rather than from the working-days arithmetic - an early-exit
     * budget overrun, an Nth-late-mark penalty.
     *
     * <p>Kept beside the total rather than folded into it silently, because the
     * two answer different questions: {@code lopDays} is what payroll pays
     * against, and this is the part somebody chose. A slip audit that cannot
     * separate "you were absent" from "you were penalised" cannot explain
     * either. The rules that produced it are in {@code
     * attendance_policy_outcome}, one row each, with the numbers they used.
     *
     * <p>Zero for every company that configures no policy rules, which is what
     * makes this column additive rather than a behaviour change.
     */
    @Column(name = "policy_lop_days", nullable = false, precision = 6, scale = 1)
    @Builder.Default
    private BigDecimal policyLopDays = BigDecimal.ZERO;

    /**
     * Compensatory-off days earned this month by working a weekly off or
     * holiday under a {@code DAY_OFF_WORK} rule.
     *
     * <p>Recorded, not yet bookable: this application has no comp-off leave
     * type to accrue into - {@code LeaveType} is a fixed enum of three - so the
     * credit is reported for HR to act on. Making it a real balance is the
     * dynamic-leave-types work, not this one. See
     * {@code docs/design/dynamic-configuration.md}.
     */
    @Column(name = "comp_off_credit_days", nullable = false, precision = 6, scale = 1)
    @Builder.Default
    private BigDecimal compOffCreditDays = BigDecimal.ZERO;
}

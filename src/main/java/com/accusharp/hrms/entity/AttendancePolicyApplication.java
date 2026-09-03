package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a day-scoped rule did to one day, and the numbers it used.
 *
 * <p>This exists because "why was I docked half a day in March" is asked in
 * September, by which time the rule may have been superseded four times. A
 * deduction the system cannot explain back to the employee it took money from
 * is not finished, so the trace is part of the feature rather than logging
 * bolted on afterwards.
 *
 * <p>Keyed on {@code (userId, attendanceDate)} - the same business key {@code
 * emp_daily_attendance} is unique on - rather than a foreign key to its {@code
 * id}. A generation run deletes this day's rows and reinserts them, so the
 * trace is rebuilt from scratch alongside the day it explains and cannot drift
 * from it.
 *
 * <p>It needs no lock or provenance flag of its own: locked and {@code MANUAL}
 * days are never regenerated, so their traces are never touched either. The
 * trace inherits the protection the day already has - the same reason {@code
 * AttendanceRecordStatus} was kept separate from the lock flag rather than
 * duplicating it.
 *
 * <p>{@link #explanation} is a rendered sentence stored beside the structured
 * numbers, not derived on read. Denormalised on purpose, exactly as {@link
 * Payroll} snapshots {@code rulePfPercent} instead of joining to {@link
 * SalaryRule}: the answer has to survive the rule that produced it changing.
 */
@Entity
@Table(name = "attendance_policy_application",
        indexes = @Index(name = "idx_policy_application_day", columnList = "user_id, attendance_date"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendancePolicyApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    /**
     * Which rule row. A plain scalar, not a JPA relation - a trace is a
     * snapshot of what happened, not a live view of a rule that has since moved
     * on. Same reasoning as {@link SalaryRevision} and {@code AuditLog}.
     */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private RuleType ruleType;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    @Column(name = "scope_ref", nullable = false, length = 50)
    private String scopeRef;

    /** Null when this rule did not touch the status - an OVERTIME rule, say. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", length = 20)
    private AttendanceStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", length = 20)
    private AttendanceStatus statusAfter;

    @Column(name = "overtime_before", precision = 6, scale = 2)
    private BigDecimal overtimeBefore;

    @Column(name = "overtime_after", precision = 6, scale = 2)
    private BigDecimal overtimeAfter;

    /** Comp-off days this day earned, when {@code DAY_OFF_WORK} credited instead of paying overtime. */
    @Column(name = "comp_off_credit", precision = 4, scale = 2)
    private BigDecimal compOffCredit;

    /**
     * The sentence an employee gets shown - "HALF_DAY: in 09:16, 1 min beyond a
     * 15 min grace on a 09:00 shift, rule LATE_ARRIVAL v1 scoped
     * CATEGORY=STAFF".
     */
    @Column(nullable = false, length = 400)
    private String explanation;
}

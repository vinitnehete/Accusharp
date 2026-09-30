package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.AttendanceTracking;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.PayrollMode;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.StatutoryDeduction;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which process a population actually follows: whether their attendance is
 * tracked at all, and where their pay comes from.
 *
 * <p>Every employee is assumed to punch, be rostered, have their attendance
 * generated and be paid from it. Real companies hold populations to different
 * standards - an owner or a director punches nothing, is rostered nothing, and
 * is paid the same salary every month with only the statutory deductions taken
 * off. Before this, payroll simply refused to run for them: it demands generated
 * attendance for every employee, so the only way through was to fabricate a
 * month of attendance for somebody who was never tracked.
 *
 * <p>Shaped exactly like {@link AttendancePolicyRule}, deliberately: the same
 * {@link RuleScope} precedence chain (most specific wins outright, no partial
 * inheritance), the same succession model (no {@code effectiveTo} - a version
 * runs until the next one starts, which is what makes overlap structurally
 * impossible), and the same empty start, so a company that configures nothing
 * is paid to the rupee as it was before this table existed.
 *
 * <p>Distinct from {@link EmploymentType}, which shapes an attendance-based
 * calculation - proration base, whether LOP applies, which overtime formula.
 * This decides whether that calculation happens at all.
 */
@Entity
@Table(name = "work_policy",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_work_policy_effective",
                        columnNames = {"company_id", "scope", "scope_ref", "effective_from"}),
                @UniqueConstraint(name = "uk_work_policy_version",
                        columnNames = {"company_id", "scope", "scope_ref", "version"})
        },
        indexes = @Index(name = "idx_work_policy_lookup",
                columnList = "company_id, scope, scope_ref, effective_from"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null is the shared fallback row, the same shape every other master uses. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    /** What the scope names - a userId, a designation code, ... - or {@link RuleScope#ANY}. */
    @Column(name = "scope_ref", nullable = false, length = 50)
    private String scopeRef;

    @Column(nullable = false)
    private int version;

    /** Always the first of a month is not required, but a policy applies from this date on. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /**
     * A disabled version is an answer, not an absence: it stops a broader scope
     * taking over, which is what an opt-out has to mean. Ending a policy appends
     * a disabled version rather than deleting the chain.
     */
    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_tracking", nullable = false, length = 20)
    private AttendanceTracking attendanceTracking;

    @Enumerated(EnumType.STRING)
    @Column(name = "payroll_mode", nullable = false, length = 20)
    private PayrollMode payrollMode;

    /**
     * Nullable, and null reads as {@link LeaveApprovalFlow#SUPERVISOR_THEN_HR} -
     * so policies written before this column existed keep the two-step flow they
     * were written under rather than silently changing who approves.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_approval", length = 30)
    private LeaveApprovalFlow leaveApproval;

    /** Statutory deductions payroll leaves out for this population; empty means all are taken. */
    @Convert(converter = StatutoryDeductionsConverter.class)
    @Column(name = "excluded_deductions", length = 100)
    @Builder.Default
    private Set<StatutoryDeduction> excludedDeductions = EnumSet.noneOf(StatutoryDeduction.class);

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(length = 500)
    private String notes;

    public boolean tracksAttendance() {
        return attendanceTracking != AttendanceTracking.NOT_TRACKED;
    }

    public boolean isFixedMonthly() {
        return payrollMode == PayrollMode.FIXED_MONTHLY;
    }

    public LeaveApprovalFlow leaveApprovalOrDefault() {
        return leaveApproval == null ? LeaveApprovalFlow.SUPERVISOR_THEN_HR : leaveApproval;
    }
}

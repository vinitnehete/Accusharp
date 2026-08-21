package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "leave_request", indexes = {
        @Index(name = "idx_leave_user_dates", columnList = "user_id,from_date,to_date"),
        @Index(name = "idx_leave_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveDuration duration;

    /** Calendar days requested, half days counted as 0.5. */
    @Column(name = "total_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal totalDays;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LeaveStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveOrigin origin;

    @Column(name = "supervisor_id", length = 50)
    private String supervisorId;

    @Column(name = "approver_id", length = 50)
    private String approverId;

    @Column(name = "approval_comments", length = 500)
    private String approvalComments;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Optimistic lock - guards against two concurrent decisions (approve/reject/cancel) on the same request. */
    @Version
    private Long version;
}

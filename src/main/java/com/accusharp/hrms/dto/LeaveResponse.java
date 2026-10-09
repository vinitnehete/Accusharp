package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code approvalFlow} is who has to agree for this employee's population, as the
 * work policy in force on the first day of leave decides it - so a client can say
 * "waiting for HR" instead of "waiting for your supervisor", and not offer an
 * endorse button the server would refuse for an {@code HR_ONLY} request.
 */
public record LeaveResponse(
        Long id,
        String userId,
        String employeeName,
        LeaveType leaveType,
        LocalDate fromDate,
        LocalDate toDate,
        LeaveDuration duration,
        BigDecimal totalDays,
        String reason,
        LeaveStatus status,
        LeaveOrigin origin,
        String supervisorId,
        String approverId,
        String approvalComments,
        Instant appliedAt,
        Instant decidedAt,
        LeaveApprovalFlow approvalFlow
) {
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
        String supervisorId,
        String approverId,
        String approvalComments,
        Instant appliedAt,
        Instant decidedAt
) {
}

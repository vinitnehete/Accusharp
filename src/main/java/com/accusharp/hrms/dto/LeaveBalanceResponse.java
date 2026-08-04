package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;

public record LeaveBalanceResponse(
        String userId,
        int leaveYear,
        LeaveType leaveType,
        BigDecimal quota,
        BigDecimal used,
        BigDecimal available
) {
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.LeaveType;

import java.math.BigDecimal;

/**
 * What closing a leave year did for one employee's leave type.
 *
 * @param available      unused at the end of the year closed
 * @param carriedForward what went into next year - never more than the rule's cap
 * @param excessToPayOut above the cap, and due for payout under the OSH Code.
 *                       Reported, not paid: paying it is a payroll change.
 * @param lapsed         above the cap under a rule set to lapse - dropped
 */
public record LeaveYearCloseRow(
        String userId,
        String employeeName,
        LeaveType leaveType,
        BigDecimal available,
        BigDecimal carriedForward,
        BigDecimal excessToPayOut,
        BigDecimal lapsed
) {
}

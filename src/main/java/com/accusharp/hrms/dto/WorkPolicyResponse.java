package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.WorkPolicy;
import com.accusharp.hrms.enums.AttendanceTracking;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.PayrollMode;
import com.accusharp.hrms.enums.RuleScope;

import java.time.LocalDate;

/**
 * One work policy version, plus a sentence a person can read - what the policy
 * actually does, rather than two enum names to interpret.
 */
public record WorkPolicyResponse(
        Long id,
        RuleScope scope,
        String scopeRef,
        int version,
        LocalDate effectiveFrom,
        boolean enabled,
        AttendanceTracking attendanceTracking,
        PayrollMode payrollMode,
        LeaveApprovalFlow leaveApproval,
        String summary,
        String notes
) {

    public static WorkPolicyResponse of(WorkPolicy policy) {
        return new WorkPolicyResponse(policy.getId(), policy.getScope(), policy.getScopeRef(),
                policy.getVersion(), policy.getEffectiveFrom(), policy.isEnabled(),
                policy.getAttendanceTracking(), policy.getPayrollMode(), policy.leaveApprovalOrDefault(),
                summarise(policy), policy.getNotes());
    }

    private static String summarise(WorkPolicy policy) {
        String attendance = policy.tracksAttendance()
                ? "Attendance is tracked"
                : "Attendance is not tracked - no roster is expected and no day is marked absent";
        String pay = policy.isFixedMonthly()
                ? "paid the salary structure for the days employed, whatever attendance says"
                : "paid from the generated attendance";
        String leave = switch (policy.leaveApprovalOrDefault()) {
            case SUPERVISOR_THEN_HR -> "leave is endorsed by their supervisor and approved by HR";
            case HR_ONLY -> "leave goes straight to HR, with no endorsement step";
            case AUTO_APPROVE -> "leave is approved as it is applied for";
        };
        return attendance + "; " + pay + "; " + leave + ".";
    }
}

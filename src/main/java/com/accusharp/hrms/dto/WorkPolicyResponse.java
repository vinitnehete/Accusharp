package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.WorkPolicy;
import com.accusharp.hrms.enums.AttendanceTracking;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.PayrollMode;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.StatutoryDeduction;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
        Set<StatutoryDeduction> excludedDeductions,
        String summary,
        String notes
) {

    public static WorkPolicyResponse of(WorkPolicy policy) {
        return new WorkPolicyResponse(policy.getId(), policy.getScope(), policy.getScopeRef(),
                policy.getVersion(), policy.getEffectiveFrom(), policy.isEnabled(),
                policy.getAttendanceTracking(), policy.getPayrollMode(), policy.leaveApprovalOrDefault(),
                policy.getExcludedDeductions(), summarise(policy), policy.getNotes());
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
        return attendance + "; " + pay + "; " + leave + notDeducted(policy.getExcludedDeductions()) + ".";
    }

    /** "; PF, ESIC and professional tax are not deducted" - or nothing. */
    private static String notDeducted(Set<StatutoryDeduction> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return "";
        }
        List<String> labels = excluded.stream().map(StatutoryDeduction::label).toList();
        String names = labels.size() == 1 ? labels.get(0)
                : String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
        return "; " + names + (labels.size() == 1 ? " is" : " are") + " not deducted";
    }
}

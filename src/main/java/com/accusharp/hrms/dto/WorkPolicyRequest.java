package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceTracking;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.PayrollMode;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.StatutoryDeduction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Creates the next version of one population's work policy. There is no update:
 * a change appends a version, and ending a policy appends a disabled one - see
 * {@link com.accusharp.hrms.entity.WorkPolicy}.
 */
@Data
public class WorkPolicyRequest {

    @NotNull
    private RuleScope scope;

    /** What the scope names - a userId, designation code, category code, department code or employment status. Ignored for COMPANY. */
    @Size(max = 50)
    private String scopeRef;

    @NotNull
    private AttendanceTracking attendanceTracking;

    @NotNull
    private PayrollMode payrollMode;

    /** Null keeps the two-step flow every company uses by default. */
    private LeaveApprovalFlow leaveApproval;

    @NotNull
    private LocalDate effectiveFrom;

    /** Null means enabled. */
    private Boolean enabled;

    /** Statutory deductions payroll leaves out for these people - PF, ESIC, professional tax, MLWF. */
    private Set<StatutoryDeduction> excludedDeductions;

    /** For {@code POST /batch}: the same policy for each of these - several employees, say. */
    private List<@Size(max = 50) String> scopeRefs;

    @Size(max = 500)
    private String notes;
}

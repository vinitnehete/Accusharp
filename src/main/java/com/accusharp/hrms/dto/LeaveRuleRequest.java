package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.CreditStep;
import com.accusharp.hrms.enums.ExcessHandling;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.LeaveRuleScope;
import com.accusharp.hrms.enums.LeaveType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Creates or edits a leave rule. The earned-leave numbers are optional: left
 * unset, the rule uses 1.5 for a full month, steps of 20 days = 1 and 10 = 0.5,
 * and the legal floor of one day per 20 worked.
 */
@Data
public class LeaveRuleRequest {

    @NotNull
    private LeaveRuleScope scope;

    /** An employment type name for {@code EMPLOYMENT_TYPE}; ignored (always {@code ANY}) for {@code COMPANY}. */
    private String scopeRef;

    @NotNull
    private LeaveType leaveType;

    @NotNull
    private LeaveGrant grantMethod;

    @DecimalMin("0")
    private BigDecimal yearlyDays;

    @DecimalMin("0")
    private BigDecimal fullMonthCredit;

    private List<CreditStep> creditSteps;

    @Min(1)
    private Integer daysPerStatutoryDay;

    @DecimalMin("0")
    private BigDecimal carryForwardCap;

    /** Above the cap at year end: pay out (the default when null) or lapse. */
    private ExcessHandling excessOverCap;

    /** Normalised to the first of its month. */
    @NotNull
    private LocalDate effectiveFrom;

    /** Null means enabled. */
    private Boolean enabled;
}

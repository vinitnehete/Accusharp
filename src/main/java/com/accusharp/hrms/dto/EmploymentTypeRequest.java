package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Creates or updates an employment type - the payroll behaviour a population is
 * paid by. See {@code EmploymentType}.
 */
@Data
public class EmploymentTypeRequest {

    @NotBlank
    @Size(max = 30)
    private String typeCode;

    @NotBlank
    @Size(max = 60)
    private String typeName;

    @NotNull
    private PayBasis payBasis;

    /**
     * The fixed monthly base a per-attended-day type prorates against. Null
     * inherits the company's {@code salaryRule.dayWiseDaysInMonth} (26 by
     * default), which is where that number lives today - set it here only to
     * give this type a different base from the rest of the company.
     */
    @Min(1)
    @Max(31)
    private Integer payableDaysCap;

    private boolean lopApplies = true;

    private boolean paidLeaveAddsPayableDays = true;

    @NotNull
    private OvertimeBasis overtimeBasis;

    private boolean paidLeaveEarnsOvertime;

    private boolean segmentedRevisionEarnings = true;

    private boolean autoRosterDefaultShift;

    private boolean active = true;
}

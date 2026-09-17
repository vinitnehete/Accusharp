package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Switches a company's leave year: 1 for January (calendar), 4 for April (financial). */
@Data
public class LeaveSettingsRequest {

    @NotNull
    private Integer leaveYearStartMonth;
}

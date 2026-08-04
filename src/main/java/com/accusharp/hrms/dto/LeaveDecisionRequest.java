package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Supervisor or HR acting on a pending leave request. */
@Data
public class LeaveDecisionRequest {

    @NotBlank
    private String approverId;

    @Size(max = 500)
    private String comments;
}

package com.accusharp.hrms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Assign many employees their own shift in one call - unlike {@link
 * BulkShiftAssignmentRequest}, which puts every selected employee on the
 * *same* shift across a date range, each entry here carries its own userId,
 * date and shiftCode, so a supervisor can build one roster upload covering a
 * whole team on different shifts (or different days) at once.
 */
@Data
public class VariedShiftAssignmentRequest {

    @NotEmpty
    @Valid
    private List<ShiftAssignmentRequest> assignments;
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.RecordStatus;

import java.time.LocalDate;

/**
 * Flattens {@code Contractor} plus the one derived figure every screen that
 * lists contractors wants: how many workers are currently deployed under it.
 */
public record ContractorResponse(
        Long id,
        String contractorCode,
        String contractorName,
        String contactPerson,
        String email,
        String phone,
        String address,
        String gstNo,
        String panNo,
        LocalDate agreementStartDate,
        LocalDate agreementEndDate,
        String notes,
        RecordStatus recordStatus,

        /** Active workers deployed under this contractor - counted, never stored. */
        long activeEmployeeCount
) {
}

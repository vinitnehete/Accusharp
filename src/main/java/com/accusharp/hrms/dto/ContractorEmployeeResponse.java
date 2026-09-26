package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;

import java.time.LocalDate;

/**
 * The contractor-worker view of an {@code Employee} row. Carries the
 * contractor's name on every row on purpose - the workforce screen lists
 * several contractors at once, and "whose worker is this" is the column that
 * makes that list readable.
 *
 * <p>Mirrors {@link ContractorEmployeeRequest}: no salary, no statutory
 * identifiers, no bank details, no role. What is not in the request is not in
 * the response either.
 */
public record ContractorEmployeeResponse(
        Long id,
        String userId,
        String employeeCode,
        String employeeName,
        Long contractorId,
        String contractorCode,
        String contractorName,
        Long designationId,
        String designationName,
        String supervisorUserId,
        String supervisorName,
        LocalDate joiningDate,
        LocalDate relievingDate,
        LocalDate dateOfBirth,
        Gender gender,
        String phone,
        RecordStatus recordStatus
) {
}

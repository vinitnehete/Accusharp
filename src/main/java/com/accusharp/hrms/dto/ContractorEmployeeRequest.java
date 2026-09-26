package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * A contractor's worker: identity, where they are deployed, and who from this
 * company supervises them. Nothing else.
 *
 * <p>Deliberately <em>not</em> {@code EmployeeRequest} with fields ignored.
 * That DTO carries gross salary, the four structure components, PF/ESIC/UAN
 * identifiers, bank details and a {@code role} - every one of which is either
 * the contractor's business rather than this company's, or a privilege
 * escalation vector on a person who has no login at all. A separate request
 * type is what makes it impossible to set any of them by accident, rather
 * than a comment asking callers not to.
 *
 * <p>{@code contractorId} is a path variable on create, not a body field, so
 * a worker can never be filed under a contractor the URL did not name.
 */
@Data
public class ContractorEmployeeRequest {

    /**
     * The biometric device user id - the only field the attendance engine
     * actually needs. Unique platform-wide, same as any employee's (see
     * {@code Employee}'s {@code uk_employee_user_id}).
     */
    @NotBlank
    @Size(max = 50)
    private String userId;

    /** The contractor's own code for this worker. Unique within the company. */
    @NotBlank
    @Size(max = 50)
    private String employeeCode;

    @NotBlank
    @Size(max = 255)
    private String employeeName;

    /** Trade or skill, from the company's designation master. Optional. */
    private Long designationId;

    /**
     * A supervisor <em>from the engaging company</em> - validated as such in
     * {@code ContractorEmployeeService}. This is the whole point of the
     * feature: our supervisor rosters and reviews their workers.
     */
    private String supervisorUserId;

    /** First day on site. Bounds nothing on its own; the roster does that. */
    private LocalDate joiningDate;

    /** Last day on site, once they leave. */
    private LocalDate relievingDate;

    private LocalDate dateOfBirth;

    private Gender gender;

    @Size(max = 20)
    private String phone;

    /** Defaults to ACTIVE on create. */
    private RecordStatus recordStatus;
}

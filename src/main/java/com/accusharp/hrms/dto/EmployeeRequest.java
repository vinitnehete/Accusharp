package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Only the fields a client may set. basicDA, hra, conveyance, education and
 * grossSalaryWage are always derived server-side from the current SalaryRule
 * and are deliberately absent here.
 */
@Data
public class EmployeeRequest {

    @NotBlank
    @Size(max = 50)
    private String userId;

    @NotBlank
    @Size(max = 50)
    private String employeeCode;

    @NotBlank
    private String employeeName;

    private Long companyId;

    private Long departmentId;

    private Long designationId;

    /** Employee.userId of the supervisor; null only for top management. */
    private String supervisorUserId;

    private LocalDate joiningDate;

    private LocalDate dateOfBirth;

    @NotNull
    private EmployeeStatus status;

    private RecordStatus recordStatus;

    private Role role;

    @Email
    private String email;

    @Size(max = 20)
    private String phone;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal grossSalary;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal pfBasic;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal medicalAllowance;

    @NotNull
    @DecimalMin(value = "0")
    private BigDecimal otherAllowance;

    private boolean overtimeEligible;
}

package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.Gender;
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
 * Only the fields a client may set. grossSalaryWage is always derived
 * server-side and is deliberately absent here. basicDA/hra/conveyanceAllowance/
 * educationAllowance are normally derived from the current SalaryRule too -
 * but a company onboarding employees from an existing payroll system may
 * already know their exact, fixed structure and not want it recalculated.
 * Supplying all four here overrides the derivation and marks the employee's
 * structure overridden, exactly as {@code PUT .../salary-structure} does.
 * Supplying only some of them is rejected - a structure that is part typed,
 * part rule-derived is not the fixed structure the caller intended.
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
    @Size(max = 255)
    private String employeeName;

    private Long companyId;

    private Long departmentId;

    private Long designationId;

    /** Optional employee grade/category - Worker, Supervisor, Manager, Director, etc. */
    private Long categoryId;

    /**
     * Optional configurable employment type, deciding how this employee is paid
     * - see {@code EmploymentType}.
     *
     * <p>Null keeps the legacy behaviour derived from {@code status}
     * (PERMANENT/DAY_WISE/CONTRACT/INTERN), which is what every existing
     * employee uses. Set it only when the company has defined its own types.
     */
    private Long employmentTypeId;

    /** Employee.userId of the supervisor; null only for top management. */
    private String supervisorUserId;

    private LocalDate joiningDate;

    private LocalDate dateOfBirth;

    private Gender gender;

    @NotNull
    private EmployeeStatus status;

    private RecordStatus recordStatus;

    private Role role;

    @Email
    private String email;

    @Size(max = 20)
    private String phone;

    // ---- statutory & bank details - all optional ----

    @Size(max = 30)
    private String uanNo;

    @Size(max = 30)
    private String esicIpNo;

    @Size(max = 30)
    private String bankAccountNo;

    @Size(max = 20)
    private String bankIfscNo;

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

    // ---- optional structure override - provide all four or none, see class Javadoc ----

    @DecimalMin(value = "0")
    private BigDecimal basicDA;

    @DecimalMin(value = "0")
    private BigDecimal hra;

    @DecimalMin(value = "0")
    private BigDecimal conveyanceAllowance;

    @DecimalMin(value = "0")
    private BigDecimal educationAllowance;
}

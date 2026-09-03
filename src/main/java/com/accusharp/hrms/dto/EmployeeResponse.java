package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Flattens the lazy master associations so the API never leaks proxies. */
public record EmployeeResponse(
        Long id,
        String userId,
        String employeeCode,
        String employeeName,
        String companyName,
        String departmentName,
        String designationName,
        String categoryName,

        /**
         * The configurable employment type assigned to this employee, if any.
         *
         * <p>Exposed because {@code EmployeeRequest.employmentTypeId} is applied
         * unconditionally on update - a client that cannot read the current value
         * back has no way to send it again, and every ordinary edit (a phone
         * number, a bank account) would silently clear the assignment. The name
         * rides along so a read-only screen does not have to fetch the whole
         * employment-type list to render one label.
         */
        Long employmentTypeId,
        String employmentTypeName,

        String supervisorUserId,
        String supervisorName,
        LocalDate joiningDate,
        LocalDate dateOfBirth,
        Gender gender,
        EmployeeStatus status,
        RecordStatus recordStatus,
        Role role,
        String email,
        String phone,
        String uanNo,
        String esicIpNo,
        String bankAccountNo,
        String bankIfscNo,
        BigDecimal grossSalary,
        BigDecimal pfBasic,
        BigDecimal basicDA,
        BigDecimal hra,
        BigDecimal conveyanceAllowance,
        BigDecimal educationAllowance,
        BigDecimal medicalAllowance,
        BigDecimal otherAllowance,
        BigDecimal grossSalaryWage,
        boolean overtimeEligible,
        boolean salaryStructureOverridden
) {
}

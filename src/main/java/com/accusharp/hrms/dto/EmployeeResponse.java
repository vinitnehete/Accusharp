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

package com.accusharp.hrms.mapper;

import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.entity.Employee;
import org.springframework.stereotype.Component;

import java.util.List;

/** Flattens the employee graph so lazy associations never reach the wire. */
@Component
public class EmployeeMapper {

    public EmployeeResponse toResponse(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getUserId(),
                employee.getEmployeeCode(),
                employee.getEmployeeName(),
                employee.getCompany() == null ? null : employee.getCompany().getCompanyName(),
                employee.getDepartment() == null ? null : employee.getDepartment().getDepartmentName(),
                employee.getDesignation() == null ? null : employee.getDesignation().getDesignationName(),
                employee.getCategory() == null ? null : employee.getCategory().getCategoryName(),
                employee.getEmploymentType() == null ? null : employee.getEmploymentType().getId(),
                employee.getEmploymentType() == null ? null : employee.getEmploymentType().getTypeName(),
                employee.getSupervisor() == null ? null : employee.getSupervisor().getUserId(),
                employee.getSupervisor() == null ? null : employee.getSupervisor().getEmployeeName(),
                employee.getJoiningDate(),
                employee.getDateOfBirth(),
                employee.getGender(),
                employee.getStatus(),
                employee.getRecordStatus(),
                employee.getRole(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getUanNo(),
                employee.getEsicIpNo(),
                employee.getBankAccountNo(),
                employee.getBankIfscNo(),
                employee.getGrossSalary(),
                employee.getPfBasic(),
                employee.getBasicDA(),
                employee.getHra(),
                employee.getConveyanceAllowance(),
                employee.getEducationAllowance(),
                employee.getMedicalAllowance(),
                employee.getOtherAllowance(),
                employee.getGrossSalaryWage(),
                employee.isOvertimeEligible(),
                employee.isSalaryStructureOverridden());
    }

    public List<EmployeeResponse> toResponses(List<Employee> employees) {
        return employees.stream().map(this::toResponse).toList();
    }
}

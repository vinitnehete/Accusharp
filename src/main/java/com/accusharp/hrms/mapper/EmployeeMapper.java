package com.accusharp.hrms.mapper;

import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.entity.Employee;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/** Flattens the employee graph so lazy associations never reach the wire. */
@Component
public class EmployeeMapper {

    public EmployeeResponse toResponse(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getUserId(),
                employee.getEmployeeCode(),
                employee.getEmployeeName(),
                nameOf(employee, e -> e.getCompany() == null ? null : e.getCompany().getCompanyName()),
                nameOf(employee, e -> e.getDepartment() == null ? null : e.getDepartment().getDepartmentName()),
                nameOf(employee, e -> e.getDesignation() == null ? null : e.getDesignation().getDesignationName()),
                nameOf(employee, e -> e.getSupervisor() == null ? null : e.getSupervisor().getUserId()),
                nameOf(employee, e -> e.getSupervisor() == null ? null : e.getSupervisor().getEmployeeName()),
                employee.getJoiningDate(),
                employee.getDateOfBirth(),
                employee.getStatus(),
                employee.getRecordStatus(),
                employee.getRole(),
                employee.getEmail(),
                employee.getPhone(),
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

    private String nameOf(Employee employee, Function<Employee, String> extractor) {
        return extractor.apply(employee);
    }
}

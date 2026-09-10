package com.accusharp.hrms.mapper;

import com.accusharp.hrms.dto.ContractorEmployeeResponse;
import com.accusharp.hrms.dto.ContractorResponse;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Flattens the contractor graph so lazy associations never reach the wire -
 * same contract as {@code EmployeeMapper}.
 *
 * <p>{@link #toEmployeeResponse} touches {@code contractor} and
 * {@code designation}/{@code supervisor}, all lazy. Every caller loads its
 * batch inside a read transaction, so the proxies resolve; the alternative
 * (a projection query per screen) would duplicate the tenant checks that
 * {@code ContractorEmployeeService} performs once.
 */
@Component
public class ContractorMapper {

    public ContractorResponse toResponse(Contractor contractor, long activeEmployeeCount) {
        return new ContractorResponse(
                contractor.getId(),
                contractor.getContractorCode(),
                contractor.getContractorName(),
                contractor.getContactPerson(),
                contractor.getEmail(),
                contractor.getPhone(),
                contractor.getAddress(),
                contractor.getGstNo(),
                contractor.getPanNo(),
                contractor.getAgreementStartDate(),
                contractor.getAgreementEndDate(),
                contractor.getNotes(),
                contractor.getRecordStatus(),
                activeEmployeeCount);
    }

    public ContractorEmployeeResponse toEmployeeResponse(Employee employee) {
        Contractor contractor = employee.getContractor();
        return new ContractorEmployeeResponse(
                employee.getId(),
                employee.getUserId(),
                employee.getEmployeeCode(),
                employee.getEmployeeName(),
                contractor == null ? null : contractor.getId(),
                contractor == null ? null : contractor.getContractorCode(),
                contractor == null ? null : contractor.getContractorName(),
                employee.getDesignation() == null ? null : employee.getDesignation().getId(),
                employee.getDesignation() == null ? null : employee.getDesignation().getDesignationName(),
                employee.getSupervisor() == null ? null : employee.getSupervisor().getUserId(),
                employee.getSupervisor() == null ? null : employee.getSupervisor().getEmployeeName(),
                employee.getJoiningDate(),
                employee.getRelievingDate(),
                employee.getDateOfBirth(),
                employee.getGender(),
                employee.getPhone(),
                employee.getRecordStatus());
    }

    public List<ContractorEmployeeResponse> toEmployeeResponses(List<Employee> employees) {
        return employees.stream().map(this::toEmployeeResponse).toList();
    }
}

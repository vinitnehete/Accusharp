package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.DepartmentRequest;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public Department create(DepartmentRequest request) {
        if (departmentRepository.existsByDepartmentCode(request.getDepartmentCode())) {
            throw new ConflictException("Department already exists with code " + request.getDepartmentCode());
        }
        return departmentRepository.save(apply(new Department(), request));
    }

    @Transactional
    public Department update(Long id, DepartmentRequest request) {
        Department department = getById(id);
        departmentRepository.findByDepartmentCode(request.getDepartmentCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another department already uses code "
                            + request.getDepartmentCode());
                });
        return departmentRepository.save(apply(department, request));
    }

    @Transactional(readOnly = true)
    public Department getById(Long id) {
        return departmentRepository.findById(id).orElseThrow(() -> NotFoundException.of("Department", id));
    }

    @Transactional(readOnly = true)
    public List<Department> getAll() {
        return departmentRepository.findAll();
    }

    @Transactional
    public void delete(Long id) {
        Department department = getById(id);
        if (!employeeRepository.findByDepartmentId(id).isEmpty()) {
            throw new ConflictException("Department still has employees assigned");
        }
        departmentRepository.delete(department);
    }

    private Department apply(Department department, DepartmentRequest request) {
        department.setDepartmentCode(request.getDepartmentCode());
        department.setDepartmentName(request.getDepartmentName());
        department.setDescription(request.getDescription());
        return department;
    }
}

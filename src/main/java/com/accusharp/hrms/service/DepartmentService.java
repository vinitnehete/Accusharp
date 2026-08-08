package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.DepartmentRequest;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * One row per company, plus shared ({@code company = null}) rows every
 * company can read - see {@link Department}'s Javadoc. Read access allows
 * both; write access ({@link #update}/{@link #delete}) is restricted to the
 * caller's own company's rows, so no company can edit a shared row or
 * another company's row - the exact gap SECURITY_AUDIT.md flagged for
 * {@code Shift} applies identically here.
 */
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;

    @Transactional
    public Department create(DepartmentRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        if (codeInUse(request.getDepartmentCode(), companyId)) {
            throw new ConflictException("Department already exists with code " + request.getDepartmentCode());
        }
        Department department = apply(new Department(), request);
        department.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
        return departmentRepository.save(department);
    }

    @Transactional
    public Department update(Long id, DepartmentRequest request) {
        Department department = getById(id);
        assertWritable(department);
        Long companyId = department.getCompany() == null ? null : department.getCompany().getId();
        findByCode(request.getDepartmentCode(), companyId)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another department already uses code "
                            + request.getDepartmentCode());
                });
        return departmentRepository.save(apply(department, request));
    }

    @Transactional(readOnly = true)
    public Department getById(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Department", id));
        assertReadable(department);
        return department;
    }

    @Transactional(readOnly = true)
    public List<Department> getAll() {
        return tenantContext.currentCompanyId()
                .map(departmentRepository::findByCompanyIdOrCompanyIsNull)
                .orElseGet(departmentRepository::findAll);
    }

    @Transactional
    public void delete(Long id) {
        Department department = getById(id);
        assertWritable(department);
        if (!employeeRepository.findByDepartmentId(id).isEmpty()) {
            throw new ConflictException("Department still has employees assigned");
        }
        departmentRepository.delete(department);
    }

    private boolean codeInUse(String code, Long companyId) {
        if (companyId == null) {
            return departmentRepository.existsByDepartmentCodeAndCompanyIsNull(code);
        }
        return departmentRepository.existsByDepartmentCodeAndCompanyId(code, companyId)
                || departmentRepository.existsByDepartmentCodeAndCompanyIsNull(code);
    }

    private Optional<Department> findByCode(String code, Long companyId) {
        if (companyId == null) {
            return departmentRepository.findByDepartmentCodeAndCompanyIsNull(code);
        }
        return departmentRepository.findByDepartmentCodeAndCompanyId(code, companyId)
                .or(() -> departmentRepository.findByDepartmentCodeAndCompanyIsNull(code));
    }

    /** A caller scoped to one company may read their own company's rows and every shared row. */
    private void assertReadable(Department department) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = department.getCompany() == null ? null : department.getCompany().getId();
            if (targetCompanyId != null && !callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Department", department.getId());
            }
        });
    }

    /** Unlike read access, a shared (company-null) row is never writable by a company caller. */
    private void assertWritable(Department department) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = department.getCompany() == null ? null : department.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Department", department.getId());
            }
        });
    }

    private Department apply(Department department, DepartmentRequest request) {
        department.setDepartmentCode(request.getDepartmentCode());
        department.setDepartmentName(request.getDepartmentName());
        department.setDescription(request.getDescription());
        return department;
    }
}

package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByCompanyIdOrCompanyIsNull(Long companyId);

    Optional<Department> findByDepartmentCodeAndCompanyId(String departmentCode, Long companyId);

    Optional<Department> findByDepartmentCodeAndCompanyIsNull(String departmentCode);

    boolean existsByDepartmentCodeAndCompanyId(String departmentCode, Long companyId);

    boolean existsByDepartmentCodeAndCompanyIsNull(String departmentCode);
}

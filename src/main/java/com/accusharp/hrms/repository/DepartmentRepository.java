package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByDepartmentCode(String departmentCode);

    boolean existsByDepartmentCode(String departmentCode);
}

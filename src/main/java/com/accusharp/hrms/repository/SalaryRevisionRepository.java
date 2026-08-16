package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.SalaryRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalaryRevisionRepository extends JpaRepository<SalaryRevision, Long> {

    List<SalaryRevision> findByEmployeeIdOrderByEffectiveDateDescCreatedAtDesc(String employeeId);
}

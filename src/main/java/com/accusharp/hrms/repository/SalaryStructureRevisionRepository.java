package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.SalaryStructureRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalaryStructureRevisionRepository extends JpaRepository<SalaryStructureRevision, Long> {

    /** Newest first - the history as an admin reads it. */
    List<SalaryStructureRevision> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);
}

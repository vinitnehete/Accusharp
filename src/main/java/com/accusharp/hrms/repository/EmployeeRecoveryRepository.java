package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.EmployeeRecovery;
import com.accusharp.hrms.enums.RecoveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EmployeeRecoveryRepository extends JpaRepository<EmployeeRecovery, Long> {

    List<EmployeeRecovery> findAllByEmployeeIdOrderByIssuedOnDescIdDesc(String employeeId);

    List<EmployeeRecovery> findAllByEmployeeIdAndStatus(String employeeId, RecoveryStatus status);

    List<EmployeeRecovery> findAllByEmployeeIdAndStatusIn(String employeeId, Collection<RecoveryStatus> statuses);

    /**
     * Batched form of {@link #findAllByEmployeeIdAndStatusIn} - one
     * {@code IN}-clause query for a whole-company payroll run or the
     * outstanding register, instead of one query per employee.
     */
    List<EmployeeRecovery> findAllByEmployeeIdInAndStatusIn(Collection<String> employeeIds, Collection<RecoveryStatus> statuses);

    List<EmployeeRecovery> findAllByEmployeeIdIn(Collection<String> employeeIds);
}

package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.RecoveryTransaction;
import com.accusharp.hrms.enums.RecoveryTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryTransactionRepository extends JpaRepository<RecoveryTransaction, Long> {

    List<RecoveryTransaction> findAllByEmployeeIdOrderByRecordedAtDescIdDesc(String employeeId);

    List<RecoveryTransaction> findAllByRecoveryIdOrderByIdAsc(Long recoveryId);

    /**
     * Every repayment a given payroll row took - what {@code
     * PayrollService.regenerate} reverses before recomputing the period, so a
     * regeneration never recovers the same instalment a second time.
     */
    List<RecoveryTransaction> findAllByPayrollIdAndType(Long payrollId, RecoveryTransactionType type);
}

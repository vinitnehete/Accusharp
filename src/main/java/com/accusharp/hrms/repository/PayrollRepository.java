package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.PayrollStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollRepository extends JpaRepository<Payroll, Long> {

    Optional<Payroll> findByEmployeeIdAndMonthAndYearAndStatus(
            String employeeId, Integer month, Integer year, PayrollStatus status);

    List<Payroll> findAllByEmployeeIdAndMonthAndYearOrderByRevisionDesc(
            String employeeId, Integer month, Integer year);

    List<Payroll> findAllByEmployeeIdOrderByYearDescMonthDesc(String employeeId);

    List<Payroll> findAllByMonthAndYearAndStatus(Integer month, Integer year, PayrollStatus status);

    long countByMonthAndYearAndStatus(Integer month, Integer year, PayrollStatus status);
}

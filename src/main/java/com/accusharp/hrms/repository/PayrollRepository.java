package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.enums.PayrollStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayrollRepository extends JpaRepository<Payroll, Long> {

    Optional<Payroll> findByEmployeeIdAndMonthAndYearAndStatus(
            String employeeId, Integer month, Integer year, PayrollStatus status);

    /**
     * Batched form of {@link #findByEmployeeIdAndMonthAndYearAndStatus} for a
     * whole-company bulk run ({@code PayrollService.generateForAll}) - one
     * {@code IN}-clause query for the "already generated?" check across every
     * employee instead of one query per employee.
     */
    List<Payroll> findAllByEmployeeIdInAndMonthAndYearAndStatus(
            Collection<String> employeeIds, Integer month, Integer year, PayrollStatus status);

    List<Payroll> findAllByEmployeeIdAndMonthAndYearOrderByRevisionDesc(
            String employeeId, Integer month, Integer year);

    List<Payroll> findAllByEmployeeIdOrderByYearDescMonthDesc(String employeeId);

    List<Payroll> findAllByMonthAndYearAndStatus(Integer month, Integer year, PayrollStatus status);

    /**
     * Every generated period for a batch of employees, newest first - for the
     * reports that span runs rather than sitting inside one: the arrears
     * report (which periods were paid at a superseded gross) and the full &
     * final worksheet (what the leaver was last paid).
     */
    List<Payroll> findAllByEmployeeIdInAndStatusOrderByYearDescMonthDesc(
            Collection<String> employeeIds, PayrollStatus status);
}

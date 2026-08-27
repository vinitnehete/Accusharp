package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.SalaryRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SalaryRevisionRepository extends JpaRepository<SalaryRevision, Long> {

    List<SalaryRevision> findByEmployeeIdOrderByEffectiveDateDescCreatedAtDesc(String employeeId);

    /** Chronological order - what payroll walks to reconstruct which gross salary applied on which day. */
    List<SalaryRevision> findByEmployeeIdOrderByEffectiveDateAsc(String employeeId);

    /**
     * Guards a re-uploaded bulk file. Two revisions for the same employee on
     * the same effective date would give payroll's segment splitter a
     * zero-width segment to walk, and the second would compute a 0% hike off
     * the gross the first already moved.
     */
    boolean existsByEmployeeIdAndEffectiveDate(String employeeId, LocalDate effectiveDate);

    /** Every revision taking effect inside a window, across a batch of employees - the salary revision report. */
    List<SalaryRevision> findAllByEmployeeIdInAndEffectiveDateBetweenOrderByEffectiveDateDesc(
            Collection<String> employeeIds, LocalDate from, LocalDate to);
}

package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUserId(String userId);

    /** employeeCode is unique per company, not globally - see Employee's uk_employee_company_code. */
    Optional<Employee> findByEmployeeCodeAndCompanyId(String employeeCode, Long companyId);

    Optional<Employee> findByEmployeeCodeAndCompanyIsNull(String employeeCode);

    boolean existsByUserId(String userId);

    boolean existsByEmployeeCodeAndCompanyId(String employeeCode, Long companyId);

    boolean existsByEmployeeCodeAndCompanyIsNull(String employeeCode);

    List<Employee> findByRecordStatus(RecordStatus recordStatus);

    List<Employee> findByRecordStatusAndCompanyId(RecordStatus recordStatus, Long companyId);

    /** Every active employee of one employment type, across every company - the default-roster job's entry point. */
    List<Employee> findByRecordStatusAndStatus(RecordStatus recordStatus, EmployeeStatus status);

    List<Employee> findBySupervisorUserId(String supervisorUserId);

    List<Employee> findByDepartmentId(Long departmentId);

    List<Employee> findByDesignationId(Long designationId);

    List<Employee> findByCategoryId(Long categoryId);

    List<Employee> findByCompanyId(Long companyId);

    @Query("""
            select e from Employee e
            where e.recordStatus = com.accusharp.hrms.enums.RecordStatus.ACTIVE
              and e.dateOfBirth is not null
              and function('month', e.dateOfBirth) = :month
            order by function('day', e.dateOfBirth)
            """)
    List<Employee> findBirthdaysInMonth(@Param("month") int month);

    @Query("""
            select e from Employee e
            where e.recordStatus = com.accusharp.hrms.enums.RecordStatus.ACTIVE
              and e.joiningDate is not null
              and function('month', e.joiningDate) = :month
              and e.joiningDate < :monthStart
            order by function('day', e.joiningDate)
            """)
    List<Employee> findWorkAnniversariesInMonth(@Param("month") int month,
                                               @Param("monthStart") LocalDate monthStart);

    /** How many employees are on an employment type - the delete guard. */
    long countByEmploymentTypeId(Long employmentTypeId);
}

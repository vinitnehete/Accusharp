package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUserId(String userId);

    Optional<Employee> findByEmployeeCode(String employeeCode);

    boolean existsByUserId(String userId);

    boolean existsByEmployeeCode(String employeeCode);

    List<Employee> findByRecordStatus(RecordStatus recordStatus);

    List<Employee> findByRecordStatusAndCompanyId(RecordStatus recordStatus, Long companyId);

    List<Employee> findBySupervisorUserId(String supervisorUserId);

    List<Employee> findByDepartmentId(Long departmentId);

    List<Employee> findByCompanyId(Long companyId);

    long countByRecordStatus(RecordStatus recordStatus);

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
}

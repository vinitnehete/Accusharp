package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DesignationRepository extends JpaRepository<Designation, Long> {

    Optional<Designation> findByDesignationCode(String designationCode);

    boolean existsByDesignationCode(String designationCode);

    List<Designation> findByCompanyIdOrCompanyIsNull(Long companyId);

    Optional<Designation> findByDesignationCodeAndCompanyId(String designationCode, Long companyId);

    Optional<Designation> findByDesignationCodeAndCompanyIsNull(String designationCode);

    boolean existsByDesignationCodeAndCompanyId(String designationCode, Long companyId);

    boolean existsByDesignationCodeAndCompanyIsNull(String designationCode);
}

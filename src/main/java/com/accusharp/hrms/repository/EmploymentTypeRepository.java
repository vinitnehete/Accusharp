package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.EmploymentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmploymentTypeRepository extends JpaRepository<EmploymentType, Long> {

    Optional<EmploymentType> findByCompanyIdAndTypeCode(Long companyId, String typeCode);

    Optional<EmploymentType> findByCompanyIsNullAndTypeCode(String typeCode);

    List<EmploymentType> findAllByCompanyIdOrderByTypeCodeAsc(Long companyId);

    List<EmploymentType> findAllByCompanyIsNullOrderByTypeCodeAsc();
}

package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    List<CustomRole> findByCompanyId(Long companyId);

    Optional<CustomRole> findByCompanyIdAndName(Long companyId, String name);

    boolean existsByCompanyIdAndName(Long companyId, String name);
}

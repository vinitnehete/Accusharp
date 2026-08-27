package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    List<CustomRole> findByCompanyId(Long companyId);

    boolean existsByCompanyIdAndName(Long companyId, String name);
}

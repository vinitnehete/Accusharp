package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.CustomRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomRolePermissionRepository extends JpaRepository<CustomRolePermission, Long> {

    List<CustomRolePermission> findByCustomRoleId(Long customRoleId);

    void deleteByCustomRoleId(Long customRoleId);
}

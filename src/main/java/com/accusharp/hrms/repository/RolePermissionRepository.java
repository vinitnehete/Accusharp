package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    @Query("select rp from RolePermission rp join fetch rp.permission")
    List<RolePermission> findAllWithPermission();
}

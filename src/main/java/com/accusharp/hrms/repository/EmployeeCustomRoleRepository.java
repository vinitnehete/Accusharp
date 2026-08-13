package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.EmployeeCustomRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface EmployeeCustomRoleRepository extends JpaRepository<EmployeeCustomRole, Long> {

    List<EmployeeCustomRole> findByEmployeeId(Long employeeId);

    List<EmployeeCustomRole> findByCustomRoleId(Long customRoleId);

    Optional<EmployeeCustomRole> findByEmployeeIdAndCustomRoleId(Long employeeId, Long customRoleId);

    void deleteByCustomRoleId(Long customRoleId);

    /**
     * Every permission code granted to {@code userId} through any custom
     * role they hold - the query {@code AuthorizationService} falls back to
     * when the base {@code Role} doesn't already grant the permission being
     * checked. Always fresh (no caching), since custom-role grants are
     * edited at runtime - see {@code CustomRolePermission}'s Javadoc.
     */
    @Query("select crp.permission.code from EmployeeCustomRole ecr "
            + "join ecr.customRole cr "
            + "join cr.grantedPermissions crp "
            + "where ecr.employee.userId = :userId")
    Set<String> findPermissionCodesForEmployeeUserId(@Param("userId") String userId);
}

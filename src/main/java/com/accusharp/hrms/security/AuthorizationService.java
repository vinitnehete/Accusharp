package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.RoleScope;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single entry point every {@code @PreAuthorize("@authz.can('...')")}
 * expression calls. Two layers: the fixed, cached {@link PermissionRegistry}
 * grant for the principal's base role (the fast path, unchanged, never hits
 * the database); if that doesn't already grant the permission and the
 * principal is a company {@code Employee}, a fallback checks whatever
 * custom roles (see {@link com.accusharp.hrms.entity.CustomRole}) they've
 * been assigned - always queried fresh, since custom-role grants are edited
 * at runtime and are not cached the way the base role's are.
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final PermissionRegistry permissionRegistry;
    private final EmployeeCustomRoleRepository employeeCustomRoleRepository;

    public boolean can(String permissionCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return false;
        }
        RoleScope scope = principal.getType() == PrincipalType.PLATFORM ? RoleScope.PLATFORM : RoleScope.COMPANY;
        if (permissionRegistry.hasPermission(scope, principal.getRole(), permissionCode)) {
            return true;
        }
        if (principal.getType() != PrincipalType.EMPLOYEE) {
            return false;
        }
        return employeeCustomRoleRepository.findPermissionCodesForEmployeeUserId(principal.getUsername())
                .contains(permissionCode);
    }
}

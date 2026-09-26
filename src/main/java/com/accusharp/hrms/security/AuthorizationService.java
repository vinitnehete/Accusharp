package com.accusharp.hrms.security;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.RoleScope;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single entry point every {@code @PreAuthorize("@authz.can('...')")}
 * expression calls. Two layers: the fixed, cached {@link PermissionRegistry}
 * grant for the principal's base role (the fast path, unchanged, never hits
 * the database); if that doesn't already grant the permission and the
 * principal is a company {@code Employee}, a fallback checks whatever
 * custom roles (see {@link com.accusharp.hrms.entity.CustomRole}) they've
 * been assigned - always queried fresh, since custom-role grants are edited
 * at runtime and are not cached the way the base role's are.
 *
 * <p>The same two layers answer {@link #effectivePermissions} (what a login
 * hands the UI) and {@link #employeeCan} (a service-layer check about an actor
 * who is not necessarily the caller), so the three can never disagree.
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

    /**
     * Everything this principal may do - the base role's grants plus, for an
     * employee, every custom role they hold. Returned at login and refresh so
     * the UI shows exactly what the API will allow, custom roles included,
     * instead of guessing from the role name. Sorted, so the response is stable.
     */
    public Set<String> effectivePermissions(UserPrincipal principal) {
        RoleScope scope = principal.getType() == PrincipalType.PLATFORM ? RoleScope.PLATFORM : RoleScope.COMPANY;
        Set<String> codes = new TreeSet<>(permissionRegistry.grantsFor(scope, principal.getRole()));
        if (principal.getType() == PrincipalType.EMPLOYEE) {
            codes.addAll(employeeCustomRoleRepository.findPermissionCodesForEmployeeUserId(principal.getUsername()));
        }
        return Collections.unmodifiableSet(codes);
    }

    /**
     * {@link #can} for a named employee rather than the current caller - for the
     * service-layer checks that take an actor id ({@code generatedBy},
     * {@code approverId}, ...), which also run in tests with no security
     * context at all.
     */
    public boolean employeeCan(Employee employee, String permissionCode) {
        if (permissionRegistry.hasPermission(RoleScope.COMPANY, employee.getRole().name(), permissionCode)) {
            return true;
        }
        return employeeCustomRoleRepository.findPermissionCodesForEmployeeUserId(employee.getUserId())
                .contains(permissionCode);
    }
}

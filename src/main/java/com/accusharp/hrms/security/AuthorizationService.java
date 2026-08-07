package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.RoleScope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single entry point every {@code @PreAuthorize("@authz.can('...')")}
 * expression calls. Deliberately just one method: permission resolution is
 * fully data-driven (see {@link PermissionRegistry}), so no endpoint needs
 * its own bespoke authorization method.
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final PermissionRegistry permissionRegistry;

    public boolean can(String permissionCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return false;
        }
        RoleScope scope = principal.getType() == PrincipalType.PLATFORM ? RoleScope.PLATFORM : RoleScope.COMPANY;
        return permissionRegistry.hasPermission(scope, principal.getRole(), permissionCode);
    }
}

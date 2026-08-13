package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.PrincipalType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The calling {@code Employee}'s company, if any - the single source every
 * tenant-isolation check in the service layer reads from.
 *
 * <p>Returns empty in three deliberately different situations, all treated
 * identically by callers (see {@code EmployeeService#assertAccessible}):
 * <ul>
 *   <li>no authenticated principal at all - true for every existing
 *       service-level test, which call services directly with no
 *       {@code SecurityContext} populated. Tenant checks no-op rather than
 *       reject, so none of that existing test coverage needed to change;</li>
 *   <li>a {@code PlatformUser} principal - platform accounts are not scoped
 *       to one company by design (see {@code SECURITY.md});</li>
 *   <li>an {@code Employee} principal whose own {@code company} is null - an
 *       edge case (an employee record predating company assignment), treated
 *       as "no tenant to enforce" rather than crashing.</li>
 * </ul>
 * A real HTTP request always has an authenticated principal by the time it
 * reaches a service method - {@code SecurityConfig} requires it - so in
 * production this is live for every request; the empty cases exist purely
 * for internal/test callers that bypass the web layer entirely.
 */
@Component
public class TenantContext {

    public Optional<Long> currentCompanyId() {
        return currentPrincipal()
                .filter(principal -> principal.getType() == PrincipalType.EMPLOYEE)
                .map(UserPrincipal::getCompanyId);
    }

    /** Used by {@code AuditService} to attribute an action - not otherwise needed outside auditing. */
    public Optional<UserPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }
}

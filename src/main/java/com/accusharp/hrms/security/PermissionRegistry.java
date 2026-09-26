package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.RoleScope;
import com.accusharp.hrms.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory copy of every {@code RolePermission} grant, loaded once after
 * startup (after {@link com.accusharp.hrms.config.PermissionSeeder} has run)
 * so {@link AuthorizationService#can} - called on every authorized request -
 * never hits the database. There is no admin API yet to edit grants at
 * runtime, so there is nothing to invalidate this cache; that arrives
 * together with dynamic role management.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionRegistry {

    private final RolePermissionRepository rolePermissionRepository;

    private volatile Map<String, Set<String>> grantsByKey = Map.of();

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        grantsByKey = rolePermissionRepository.findAllWithPermission().stream()
                .collect(Collectors.groupingBy(
                        rp -> key(rp.getRoleScope(), rp.getRoleName()),
                        Collectors.mapping(rp -> rp.getPermission().getCode(),
                                Collectors.toUnmodifiableSet())));
        log.info("permission.registry loaded roles={} totalGrants={}", grantsByKey.size(),
                grantsByKey.values().stream().mapToInt(Set::size).sum());
    }

    public boolean hasPermission(RoleScope scope, String roleName, String permissionCode) {
        return grantsFor(scope, roleName).contains(permissionCode);
    }

    /** Every permission code the role holds on its own, before any custom role. */
    public Set<String> grantsFor(RoleScope scope, String roleName) {
        return grantsByKey.getOrDefault(key(scope, roleName), Set.of());
    }

    private String key(RoleScope scope, String roleName) {
        return scope.name() + ":" + roleName;
    }
}

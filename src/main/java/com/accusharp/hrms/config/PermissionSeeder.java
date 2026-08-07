package com.accusharp.hrms.config;

import com.accusharp.hrms.entity.Permission;
import com.accusharp.hrms.entity.RolePermission;
import com.accusharp.hrms.enums.PermissionCode;
import com.accusharp.hrms.enums.PlatformRole;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.enums.RoleScope;
import com.accusharp.hrms.repository.PermissionRepository;
import com.accusharp.hrms.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the fixed permission catalog and the role -> permission grants that
 * reproduce today's business rules as explicit, inspectable data instead of
 * scattered {@code if (role == Role.HR)} checks.
 *
 * <p>Two gaps in the pre-existing business logic are deliberately closed
 * here rather than preserved: {@code LeaveService.reject}/{@code cancel} had
 * no role check at all (unlike {@code approve}, which required HR/ADMIN) -
 * both now require {@code LEAVE_APPROVE}, same as approval.
 *
 * <p>Always idempotent and always brings an existing database's grants back
 * in line with this file on startup - it deletes and re-inserts every
 * {@code RolePermission} row rather than only adding missing ones, so a role
 * whose access is <em>narrowed</em> here actually narrows on restart instead
 * of leaving stale over-broad grants behind. {@link Permission} rows
 * themselves are additive-only (a code already referenced by
 * {@code @PreAuthorize} must never disappear out from under a running app).
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class PermissionSeeder {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Bean
    @Order(1) // before DataSeeder, which does not depend on this but keeps startup logs in a sane order
    ApplicationRunner seedPermissions() {
        return args -> {
            Map<PermissionCode, Permission> catalog = seedCatalog();
            seedGrants(catalog);
        };
    }

    private Map<PermissionCode, Permission> seedCatalog() {
        Map<PermissionCode, Permission> catalog = new HashMap<>();
        for (PermissionCode code : PermissionCode.values()) {
            Permission permission = permissionRepository.findByCode(code.name())
                    .orElseGet(() -> permissionRepository.save(Permission.builder()
                            .code(code.name())
                            .description(code.name().replace('_', ' '))
                            .build()));
            catalog.put(code, permission);
        }
        return catalog;
    }

    private void seedGrants(Map<PermissionCode, Permission> catalog) {
        Map<String, Set<PermissionCode>> grants = new HashMap<>();

        // ADMIN and HR are functionally identical in every documented rule in this app today - see the
        // authorization matrix in SECURITY.md - with one deliberate exception: neither holds
        // COMPANY_CREATE/UPDATE/DELETE, which is platform-only. Sharing one set keeps that true by
        // construction; EnumSet.allOf(...) here previously handed ADMIN those platform-only permissions
        // too, undetected until CompanyOnboardingHttpTest#onboardingIsPlatformOnly caught it.
        Set<PermissionCode> companyAdminPermissions = EnumSet.of(
                PermissionCode.COMPANY_READ,
                PermissionCode.DEPARTMENT_MANAGE, PermissionCode.DEPARTMENT_READ,
                PermissionCode.DESIGNATION_MANAGE, PermissionCode.DESIGNATION_READ,
                PermissionCode.EMPLOYEE_CREATE, PermissionCode.EMPLOYEE_READ,
                PermissionCode.EMPLOYEE_UPDATE, PermissionCode.EMPLOYEE_DELETE,
                PermissionCode.SHIFT_MANAGE, PermissionCode.SHIFT_READ,
                PermissionCode.SHIFT_SCHEDULE_MANAGE, PermissionCode.SHIFT_SCHEDULE_READ,
                PermissionCode.ATTENDANCE_READ, PermissionCode.ATTENDANCE_GENERATE,
                PermissionCode.ATTENDANCE_CORRECT, PermissionCode.ATTENDANCE_UNLOCK,
                PermissionCode.HOLIDAY_MANAGE, PermissionCode.HOLIDAY_READ,
                PermissionCode.LEAVE_APPLY, PermissionCode.LEAVE_READ,
                PermissionCode.LEAVE_SUPERVISOR_APPROVE, PermissionCode.LEAVE_APPROVE,
                PermissionCode.LEAVE_BALANCE_READ, PermissionCode.LEAVE_BALANCE_MANAGE,
                PermissionCode.SALARY_RULE_READ, PermissionCode.SALARY_RULE_MANAGE,
                PermissionCode.PAYROLL_PROCESS, PermissionCode.PAYROLL_READ,
                PermissionCode.SALARY_SLIP_READ,
                PermissionCode.REPORT_READ,
                PermissionCode.DASHBOARD_READ);

        grants.put(Role.HR.name(), companyAdminPermissions);

        // ADMIN gets everything HR does, plus AUDIT_READ - deliberately not shared with HR,
        // since the audit trail should include HR's own actions, not be self-reviewable by HR.
        Set<PermissionCode> adminPermissions = EnumSet.copyOf(companyAdminPermissions);
        adminPermissions.add(PermissionCode.AUDIT_READ);
        grants.put(Role.ADMIN.name(), adminPermissions);

        grants.put(Role.SUPERVISOR.name(), EnumSet.of(
                PermissionCode.COMPANY_READ,
                PermissionCode.DEPARTMENT_READ,
                PermissionCode.DESIGNATION_READ,
                PermissionCode.EMPLOYEE_READ,
                PermissionCode.SHIFT_READ,
                PermissionCode.SHIFT_SCHEDULE_MANAGE, PermissionCode.SHIFT_SCHEDULE_READ,
                PermissionCode.ATTENDANCE_READ,
                PermissionCode.HOLIDAY_READ,
                PermissionCode.LEAVE_APPLY, PermissionCode.LEAVE_READ,
                PermissionCode.LEAVE_SUPERVISOR_APPROVE,
                PermissionCode.LEAVE_BALANCE_READ,
                PermissionCode.PAYROLL_READ,
                PermissionCode.SALARY_SLIP_READ,
                PermissionCode.REPORT_READ,
                PermissionCode.DASHBOARD_READ));

        grants.put(Role.EMPLOYEE.name(), EnumSet.of(
                PermissionCode.COMPANY_READ,
                PermissionCode.DEPARTMENT_READ,
                PermissionCode.DESIGNATION_READ,
                PermissionCode.EMPLOYEE_READ,
                PermissionCode.SHIFT_READ,
                PermissionCode.SHIFT_SCHEDULE_READ,
                PermissionCode.ATTENDANCE_READ,
                PermissionCode.HOLIDAY_READ,
                PermissionCode.LEAVE_APPLY, PermissionCode.LEAVE_READ,
                PermissionCode.LEAVE_BALANCE_READ,
                PermissionCode.SALARY_SLIP_READ));

        grants.put(PlatformRole.PLATFORM_OWNER.name(), EnumSet.of(
                PermissionCode.COMPANY_CREATE, PermissionCode.COMPANY_READ,
                PermissionCode.COMPANY_UPDATE, PermissionCode.COMPANY_DELETE,
                PermissionCode.AUDIT_READ));

        grants.put(PlatformRole.PLATFORM_ADMIN.name(), EnumSet.of(
                PermissionCode.COMPANY_CREATE, PermissionCode.COMPANY_READ,
                PermissionCode.COMPANY_UPDATE, PermissionCode.COMPANY_DELETE,
                PermissionCode.AUDIT_READ));

        rolePermissionRepository.deleteAll();
        for (Role role : Role.values()) {
            saveGrant(RoleScope.COMPANY, role.name(), grants.getOrDefault(role.name(), Set.of()), catalog);
        }
        for (PlatformRole role : PlatformRole.values()) {
            saveGrant(RoleScope.PLATFORM, role.name(), grants.getOrDefault(role.name(), Set.of()), catalog);
        }
        log.info("seed.permissions codes={} grants={}", catalog.size(), rolePermissionRepository.count());
    }

    private void saveGrant(RoleScope scope, String roleName, Set<PermissionCode> codes,
                           Map<PermissionCode, Permission> catalog) {
        for (PermissionCode code : codes) {
            rolePermissionRepository.save(RolePermission.builder()
                    .roleScope(scope)
                    .roleName(roleName)
                    .permission(catalog.get(code))
                    .build());
        }
    }
}

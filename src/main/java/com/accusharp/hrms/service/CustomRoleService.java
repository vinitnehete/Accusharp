package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.CustomRoleRequest;
import com.accusharp.hrms.dto.CustomRoleResponse;
import com.accusharp.hrms.entity.CustomRole;
import com.accusharp.hrms.entity.CustomRolePermission;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmployeeCustomRole;
import com.accusharp.hrms.entity.Permission;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PermissionCode;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.CustomRolePermissionRepository;
import com.accusharp.hrms.repository.CustomRoleRepository;
import com.accusharp.hrms.repository.EmployeeCustomRoleRepository;
import com.accusharp.hrms.repository.PermissionRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dynamic role/permission management: a company can define named "custom
 * roles", grant each one an arbitrary set of {@link Permission}s, and
 * assign any number of them to any of its employees - additive on top of
 * the employee's fixed {@link com.accusharp.hrms.enums.Role}, never a
 * replacement for it. See {@link CustomRole}'s Javadoc for why additive
 * rather than replacing the enum, and {@code AuthorizationService} for how
 * a custom role's grants actually take effect.
 *
 * <p><b>Platform-only permissions can never be granted through a custom
 * role</b> - {@link #PLATFORM_ONLY_CODES} - otherwise a company ADMIN could
 * hand themselves {@code COMPANY_DELETE} (touch any company on the
 * platform) or {@code AUDIT_MANAGE} (purge the very trail meant to hold
 * them accountable) through a mechanism meant only to compose *company*-
 * scoped capabilities.
 */
@Service
@RequiredArgsConstructor
public class CustomRoleService {

    private static final Set<PermissionCode> PLATFORM_ONLY_CODES = EnumSet.of(
            PermissionCode.COMPANY_CREATE, PermissionCode.COMPANY_UPDATE,
            PermissionCode.COMPANY_DELETE, PermissionCode.AUDIT_MANAGE);

    private final CustomRoleRepository customRoleRepository;
    private final CustomRolePermissionRepository customRolePermissionRepository;
    private final EmployeeCustomRoleRepository employeeCustomRoleRepository;
    private final PermissionRepository permissionRepository;
    private final CompanyRepository companyRepository;
    private final EmployeeService employeeService;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    @Transactional
    public CustomRoleResponse create(CustomRoleRequest request) {
        Long companyId = requireCompanyId();
        if (customRoleRepository.existsByCompanyIdAndName(companyId, request.getName())) {
            throw new ConflictException("A custom role named " + request.getName() + " already exists");
        }
        CustomRole role = CustomRole.builder()
                .company(companyRepository.getReferenceById(companyId))
                .name(request.getName())
                .description(request.getDescription())
                .build();
        CustomRole saved = customRoleRepository.save(role);
        auditService.record("CUSTOM_ROLE_CREATE", "CustomRole", saved.getName(), AuditOutcome.SUCCESS, null);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CustomRoleResponse> list() {
        Long companyId = requireCompanyId();
        return customRoleRepository.findByCompanyId(companyId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CustomRoleResponse getById(Long id) {
        return toResponse(getEntity(id));
    }

    /** Replaces the role's entire permission set - not additive per call, so a client always knows the resulting grant exactly. */
    @Transactional
    public CustomRoleResponse setPermissions(Long id, Set<String> codes) {
        CustomRole role = getEntity(id);

        Set<String> platformOnlyRequested = codes.stream()
                .filter(code -> PLATFORM_ONLY_CODES.stream().anyMatch(platformCode -> platformCode.name().equals(code)))
                .collect(Collectors.toSet());
        if (!platformOnlyRequested.isEmpty()) {
            throw new BusinessRuleException(
                    "Cannot grant platform-only permissions through a custom role: " + platformOnlyRequested);
        }

        List<Permission> permissions = codes.stream()
                .map(code -> permissionRepository.findByCode(code)
                        .orElseThrow(() -> new BusinessRuleException("Unknown permission code " + code)))
                .toList();

        customRolePermissionRepository.deleteByCustomRoleId(id);
        customRolePermissionRepository.saveAll(permissions.stream()
                .map(permission -> CustomRolePermission.builder().customRole(role).permission(permission).build())
                .toList());

        auditService.record("CUSTOM_ROLE_SET_PERMISSIONS", "CustomRole", role.getName(),
                AuditOutcome.SUCCESS, "permissions=" + codes);
        return toResponse(role);
    }

    @Transactional
    public void assignToEmployee(Long id, String userId) {
        CustomRole role = getEntity(id);
        Employee employee = employeeService.getEntityByUserId(userId);
        if (employeeCustomRoleRepository.findByEmployeeIdAndCustomRoleId(employee.getId(), id).isEmpty()) {
            employeeCustomRoleRepository.save(
                    EmployeeCustomRole.builder().employee(employee).customRole(role).build());
        }
        auditService.record("CUSTOM_ROLE_ASSIGN", "CustomRole", role.getName(), AuditOutcome.SUCCESS,
                "userId=" + userId);
    }

    @Transactional
    public void unassignFromEmployee(Long id, String userId) {
        CustomRole role = getEntity(id);
        Employee employee = employeeService.getEntityByUserId(userId);
        employeeCustomRoleRepository.findByEmployeeIdAndCustomRoleId(employee.getId(), id)
                .ifPresent(employeeCustomRoleRepository::delete);
        auditService.record("CUSTOM_ROLE_UNASSIGN", "CustomRole", role.getName(), AuditOutcome.SUCCESS,
                "userId=" + userId);
    }

    @Transactional(readOnly = true)
    public List<CustomRoleResponse> listForEmployee(String userId) {
        Employee employee = employeeService.getEntityByUserId(userId);
        return employeeCustomRoleRepository.findByEmployeeId(employee.getId()).stream()
                .map(assignment -> toResponse(assignment.getCustomRole()))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        CustomRole role = getEntity(id);
        employeeCustomRoleRepository.deleteByCustomRoleId(id);
        customRolePermissionRepository.deleteByCustomRoleId(id);
        customRoleRepository.delete(role);
        auditService.record("CUSTOM_ROLE_DELETE", "CustomRole", role.getName(), AuditOutcome.SUCCESS, null);
    }

    private CustomRole getEntity(Long id) {
        CustomRole role = customRoleRepository.findById(id).orElseThrow(() -> NotFoundException.of("CustomRole", id));
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            if (!callerCompanyId.equals(role.getCompany().getId())) {
                throw NotFoundException.of("CustomRole", id);
            }
        });
        return role;
    }

    private Long requireCompanyId() {
        return tenantContext.currentCompanyId()
                .orElseThrow(() -> new BusinessRuleException("Custom roles require a company-scoped caller"));
    }

    private CustomRoleResponse toResponse(CustomRole role) {
        Set<String> codes = role.getGrantedPermissions().stream()
                .map(grant -> grant.getPermission().getCode())
                .collect(Collectors.toSet());
        return new CustomRoleResponse(role.getId(), role.getName(), role.getDescription(), codes);
    }
}

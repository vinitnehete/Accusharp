package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.CustomRolePermissionsRequest;
import com.accusharp.hrms.dto.CustomRoleRequest;
import com.accusharp.hrms.dto.CustomRoleResponse;
import com.accusharp.hrms.service.CustomRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Dynamic role/permission management: create a company-scoped custom role,
 * grant it a set of permissions, and assign it to employees - additive on
 * top of their fixed {@code Role}, not a replacement for it. See
 * {@code CustomRoleService}'s Javadoc and SECURITY.md.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class CustomRoleController {

    private final CustomRoleService customRoleService;

    @PreAuthorize("@authz.can('ROLE_MANAGE')")
    @PostMapping
    public ResponseEntity<CustomRoleResponse> create(@Valid @RequestBody CustomRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customRoleService.create(request));
    }

    @PreAuthorize("@authz.can('ROLE_READ')")
    @GetMapping
    public List<CustomRoleResponse> list() {
        return customRoleService.list();
    }

    @PreAuthorize("@authz.can('ROLE_READ')")
    @GetMapping("/{id}")
    public CustomRoleResponse getById(@PathVariable Long id) {
        return customRoleService.getById(id);
    }

    @PreAuthorize("@authz.can('ROLE_MANAGE')")
    @PutMapping("/{id}/permissions")
    public CustomRoleResponse setPermissions(@PathVariable Long id,
                                             @Valid @RequestBody CustomRolePermissionsRequest request) {
        return customRoleService.setPermissions(id, request.getPermissionCodes());
    }

    @PreAuthorize("@authz.can('ROLE_MANAGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        customRoleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.can('ROLE_MANAGE')")
    @PostMapping("/{id}/employees/{userId}")
    public ResponseEntity<Void> assign(@PathVariable Long id, @PathVariable String userId) {
        customRoleService.assignToEmployee(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.can('ROLE_MANAGE')")
    @DeleteMapping("/{id}/employees/{userId}")
    public ResponseEntity<Void> unassign(@PathVariable Long id, @PathVariable String userId) {
        customRoleService.unassignFromEmployee(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.can('ROLE_READ')")
    @GetMapping("/employees/{userId}")
    public List<CustomRoleResponse> listForEmployee(@PathVariable String userId) {
        return customRoleService.listForEmployee(userId);
    }
}

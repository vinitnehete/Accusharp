package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @PreAuthorize("@authz.can('EMPLOYEE_CREATE')")
    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                    @Valid @RequestBody EmployeeRequest request) {
        assertNotGrantingAdminUnlessAdmin(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
    }

    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PutMapping("/{id}")
    public EmployeeResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        assertNotGrantingAdminUnlessAdmin(principal, request);
        return employeeService.update(id, request);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable Long id) {
        return employeeService.getById(id);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/by-user-id/{userId}")
    public EmployeeResponse getByUserId(@PathVariable String userId) {
        return employeeService.getByUserId(userId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping
    public List<EmployeeResponse> getAll() {
        return employeeService.getAll();
    }

    /** The supervisor's team - the basis of every approval flow. */
    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/{supervisorUserId}/team")
    public List<EmployeeResponse> getTeam(@PathVariable String supervisorUserId) {
        return employeeService.getTeamOf(supervisorUserId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PatchMapping("/{userId}/supervisor")
    public EmployeeResponse assignSupervisor(@PathVariable String userId,
                                             @RequestParam(required = false) String supervisorUserId) {
        return employeeService.assignSupervisor(userId, supervisorUserId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_DELETE')")
    @DeleteMapping("/{id}")
    public EmployeeResponse deactivate(@PathVariable Long id) {
        return employeeService.deactivate(id);
    }

    /**
     * Self-escalation guard (HRMS spec §7): granting the ADMIN role is
     * itself an ADMIN-only action, so HR - which otherwise has full
     * EMPLOYEE_CREATE/UPDATE rights - cannot mint a new admin account or
     * promote itself to one. Kept here rather than in EmployeeService so the
     * many existing service-level tests that call
     * {@code EmployeeService.create/update} directly (with no
     * SecurityContext populated) are unaffected.
     */
    private void assertNotGrantingAdminUnlessAdmin(UserPrincipal principal, EmployeeRequest request) {
        if (principal == null) {
            throw new AuthenticationFailedException("Authentication is required");
        }
        if (request.getRole() == Role.ADMIN && !Role.ADMIN.name().equals(principal.getRole())) {
            throw new AccessDeniedException("Only an ADMIN may grant the ADMIN role");
        }
    }
}
